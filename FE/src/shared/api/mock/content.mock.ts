// Mock bài viết: phần công khai (T46, UC17: content-v1 #15–17; BR-BV-03, BR-BV-04, BR-CK-01) và quản trị chuyên mục, bài viết
// (T47, UC81: content-v1 #3–13; BR-BV-01…04, chỉ SUPER_MANAGER). Luật, câu báo lỗi và thứ tự kiểm chép từ BE (ArticleService,
// ArticleCategoryService, PublicArticleService, DTO Bean Validation). Path công khai: token gửi kèm bị bỏ qua (ADR-0005).
// State (chuyên mục + bài DRAFT / PUBLISHED / HIDDEN) ở localStorage `CONTENT_STATE_KEY`, dữ liệu mẫu ở content.seed.ts, và luôn
// được đọc lại mỗi request: bài vừa đăng / gỡ ở màn quản trị hiện / mất ngay ở trang công khai. Bộ lọc HTML ở content-html.ts.
//
// Thứ tự kiểm (quản trị): 401 (mock-api) → 400 body / query / path (đọc body, kiểu, Bean Validation) → 403 vai trò → 404 →
// 400 rule BR-* → 409. Riêng tạo bài, BE lọc nội dung trước khi tra chuyên mục: nội dung trống sau lọc (BR-BV-01) → chuyên mục
// lạ (404) → slug trùng (BR-BV-01). Sửa bài: chuyên mục lạ → bài lạ (404) → khóa slug → slug trùng → nội dung trống. Lỗi thì không
// ghi gì (mọi kiểm tra xong mới đổi state).
//
// Các điểm hợp đồng im lặng (ASSUMPTION):
//  - Slug sai định dạng ở /articles/:slug không bị từ chối riêng: BE tra thẳng theo slug nên cũng là 404 như slug lạ.
//  - `categorySlug` tra trong mọi chuyên mục, kể cả ẩn (D6 của T32); chỉ riêng danh sách chuyên mục công khai bỏ chuyên mục ẩn.
//  - Một trường vi phạm cả "không được trống" lẫn "sai định dạng" chỉ báo câu "không được trống" (BE báo cả hai, thứ tự không cố định).
//  - Trường sai kiểu JSON (categoryId là chữ, isHidden không phải boolean, body không phải object) là 400 MALFORMED_REQUEST; BE còn
//    ép kiểu số / chữ cho nhau, FE không bao giờ gửi vậy.
//  - `updatedAt` chỉ đổi khi dữ liệu thực sự đổi (BE: @LastModifiedDate chạy khi entity có thay đổi); đổi trạng thái luôn đổi.
//  - Chuyên mục ẩn vẫn nhận bài mới / bài chuyển vào (BE không chặn).
import type { Article, ArticleCategory, ArticleSummary } from '../../types/content';
import type { AccountSummary } from '../../types/auth';
import type { PublicArticle, PublicArticleCard } from '../../types/public-site';
import { cleanArticleHtml, hasArticleText } from './content-html';
import { type ArticleCategoryRecord, type ArticleRecord, type ContentState, CONTENT_STATE_KEY, seedContent } from './content.seed';
import {
  blank,
  denied,
  fail,
  has,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  ruleFail,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
} from './envelope';
import { clampPaging, pageOf, typeError } from './public.mock';

function load(): ContentState {
  try {
    const raw = localStorage.getItem(CONTENT_STATE_KEY);
    // Gộp với dữ liệu mẫu: state ghi bởi bản cũ mà thiếu trường (vd `seq`) vẫn đọc được.
    return raw ? { ...seedContent(), ...(JSON.parse(raw) as Partial<ContentState>) } : seedContent();
  } catch {
    return seedContent();
  }
}

function save(state: ContentState) {
  try {
    localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(state));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Công khai (T46) ───

/** Chuyên mục không ẩn, theo tên rồi id. */
const listPublicArticleCategories: MockHandler = () =>
  ok(
    load()
      .categories.filter((c) => !c.isHidden)
      .sort((a, b) => a.name.localeCompare(b.name, 'vi') || a.categoryId - b.categoryId)
      .map(({ categoryId, name, slug }) => ({ categoryId, name, slug })),
  );

/** Dựng từng trường (không trải bản ghi) để `status`, `content`… không lọt vào thẻ bài. */
function cardOf(a: ArticleRecord, { categories }: ContentState): PublicArticleCard {
  const category = categories.find((c) => c.categoryId === a.categoryId)!;
  return {
    articleId: a.articleId,
    title: a.title,
    slug: a.slug,
    categoryName: category.name,
    categorySlug: category.slug,
    coverImageUrl: a.coverImageUrl,
    summary: a.summary,
    authorDisplayName: a.authorDisplayName,
    publishedAt: a.firstPublishedAt!,
  };
}

/** Chỉ PUBLISHED, xuất bản lần đầu mới nhất trước (rồi id giảm dần); `size` kẹp 1..100 như BE. */
const listPublicArticles: MockHandler = ({ query }) => {
  const bad = typeError(query, ['page', 'size']);
  if (bad) return bad;
  const state = load();
  const paging = clampPaging(query);
  const filtering = !!query.categorySlug?.trim();
  const category = filtering ? state.categories.find((c) => c.slug === query.categorySlug) : undefined;
  if (filtering && !category) return ok(pageOf([], paging));
  const found = state.articles
    .filter((a) => a.status === 'PUBLISHED' && (!category || a.categoryId === category.categoryId))
    .sort((a, b) => Date.parse(b.firstPublishedAt!) - Date.parse(a.firstPublishedAt!) || b.articleId - a.articleId);
  return ok(pageOf(found.map((a) => cardOf(a, state)), paging));
};

/** Bài DRAFT, HIDDEN và slug lạ (kể cả sai định dạng) cùng một 404; bài trong chuyên mục ẩn vẫn đọc được. */
const getPublicArticle: MockHandler = ({ params }) => {
  const state = load();
  const article = state.articles.find((a) => a.slug === params.slug && a.status === 'PUBLISHED');
  if (!article) return notFound('bài viết', params.slug);
  const data: PublicArticle = { ...cardOf(article, state), content: article.content };
  return ok(data);
};

// ─── Quản trị: đọc dữ liệu vào ───

type Body = Record<string, unknown>;

const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const SLUG_MESSAGE = 'Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang';
/** Ảnh bìa: `^https://\S+$` (chuỗi rỗng chỉ hợp lệ ở PATCH, nơi nó có nghĩa là xóa). */
const COVER_URL = /^https:\/\/\S+$/;
const STATUSES: readonly string[] = ['DRAFT', 'PUBLISHED', 'HIDDEN'];

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);

/** Body phải là object và các trường đã gửi đúng kiểu; không thì `null` (BE: HttpMessageNotReadableException). */
function readBody(body: unknown, kinds: { text?: string[]; int?: string[]; bool?: string[] }): Body | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const b = body as Body;
  const wrong = (names: string[] | undefined, isRight: (v: unknown) => boolean) => (names ?? []).some((n) => has(b[n]) && !isRight(b[n]));
  const bad = wrong(kinds.text, (v) => typeof v === 'string') || wrong(kinds.int, Number.isInteger) || wrong(kinds.bool, (v) => typeof v === 'boolean');
  return bad ? null : b;
}

const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
/** NFC rồi strip như `ContentText.normalize` của BE. */
const norm = (s: string) => s.normalize('NFC').trim();
const blankToNull = (s: string | null | undefined) => (s == null || s.trim() === '' ? null : s);
/** Giờ hiện tại dạng ISO có offset +07:00 như dữ liệu mẫu (không phụ thuộc múi giờ máy, không có phần nghìn giây). */
const nowIso = () => new Date(Date.now() + 7 * 3_600_000).toISOString().slice(0, 19) + '+07:00';

/** `@PreAuthorize("hasRole('SUPER_MANAGER')")`: ADMIN kỹ thuật cũng bị chặn. */
const guard = (account: AccountSummary | null): MockReply | null => (account?.role === 'SUPER_MANAGER' ? null : denied());

const slugChecks = (v: unknown, max: number, required: boolean): Check[] => [
  ['slug', 'Vui lòng nhập đường dẫn', required && !isText(v)],
  ['slug', `Đường dẫn tối đa ${max} ký tự`, tooLong(v, max)],
  ['slug', SLUG_MESSAGE, typeof v === 'string' && !SLUG.test(v) && (!required || isText(v))],
];

const createArticleChecks = (b: Body): Check[] => [
  ['categoryId', 'Vui lòng chọn chuyên mục', !has(b.categoryId)],
  ['categoryId', 'Mã chuyên mục phải là số dương', isInt(b.categoryId) && b.categoryId <= 0],
  ['title', 'Vui lòng nhập tiêu đề', !isText(b.title)],
  ['title', 'Tiêu đề tối đa 250 ký tự', tooLong(b.title, 250)],
  ...slugChecks(b.slug, 270, true),
  ['coverImageUrl', 'Địa chỉ ảnh bìa tối đa 500 ký tự', tooLong(b.coverImageUrl, 500)],
  ['coverImageUrl', 'Ảnh bìa phải là địa chỉ https', typeof b.coverImageUrl === 'string' && !COVER_URL.test(b.coverImageUrl)],
  ['summary', 'Tóm tắt tối đa 500 ký tự', tooLong(b.summary, 500)],
  ['content', 'Vui lòng nhập nội dung', !isText(b.content)],
  ['authorDisplayName', 'Vui lòng nhập tên tác giả hiển thị', !isText(b.authorDisplayName)],
  ['authorDisplayName', 'Tên tác giả tối đa 100 ký tự', tooLong(b.authorDisplayName, 100)],
];

/** PATCH: trường vắng hoặc null được bỏ qua; trường đã gửi không được trống (trừ ảnh bìa, tóm tắt: chuỗi rỗng = xóa). */
const updateArticleChecks = (b: Body): Check[] => [
  ['categoryId', 'Mã chuyên mục phải là số dương', isInt(b.categoryId) && b.categoryId <= 0],
  ['title', 'Tiêu đề tối đa 250 ký tự', tooLong(b.title, 250)],
  ['title', 'Tiêu đề không được để trống', blank(b.title)],
  ...slugChecks(b.slug, 270, false),
  ['coverImageUrl', 'Địa chỉ ảnh bìa tối đa 500 ký tự', tooLong(b.coverImageUrl, 500)],
  ['coverImageUrl', 'Ảnh bìa phải là địa chỉ https', typeof b.coverImageUrl === 'string' && b.coverImageUrl !== '' && !COVER_URL.test(b.coverImageUrl)],
  ['summary', 'Tóm tắt tối đa 500 ký tự', tooLong(b.summary, 500)],
  ['content', 'Nội dung không được để trống', blank(b.content)],
  ['authorDisplayName', 'Tên tác giả tối đa 100 ký tự', tooLong(b.authorDisplayName, 100)],
  ['authorDisplayName', 'Tên tác giả không được để trống', blank(b.authorDisplayName)],
];

/** `ArticleService.cleanContent`: NFC + strip, lọc HTML; `null` là trống sau khi lọc (BR-BV-01). */
function cleanContent(html: string): string | null {
  const cleaned = cleanArticleHtml(norm(html));
  return hasArticleText(cleaned) ? cleaned : null;
}

const emptyContent = () => ruleFail('BR-BV-01', 'Nội dung bài viết trống sau khi lọc');

// ─── Quản trị: chuyên mục (#3–6) ───

const categoryOf = (c: ArticleCategoryRecord, { articles }: ContentState): ArticleCategory => ({
  categoryId: c.categoryId,
  name: c.name,
  slug: c.slug,
  isHidden: c.isHidden,
  articleCount: articles.filter((a) => a.categoryId === c.categoryId).length,
});

const listCategories: MockHandler = ({ account }) => {
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const byName = (a: ArticleCategoryRecord, b: ArticleCategoryRecord) => a.name.localeCompare(b.name, 'vi') || a.categoryId - b.categoryId;
  return ok([...state.categories].sort(byName).map((c) => categoryOf(c, state)));
};

/** Tên và slug trùng là 400 VALIDATION_FAILED (hợp đồng không gắn mã rule cho chuyên mục); tên so sánh đúng từng ký tự như UNIQUE. */
const createCategory: MockHandler = ({ body, account }) => {
  const b = readBody(body, { text: ['name', 'slug'] });
  if (!b) return malformed();
  const bad = invalid([
    ['name', 'Vui lòng nhập tên chuyên mục', !isText(b.name)],
    ['name', 'Tên chuyên mục tối đa 100 ký tự', tooLong(b.name, 100)],
    ...slugChecks(b.slug, 120, true),
  ]);
  if (bad) return bad;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const name = norm(b.name as string);
  if (state.categories.some((c) => c.name === name)) return fail('VALIDATION_FAILED', 'Tên chuyên mục đã tồn tại', 400);
  if (state.categories.some((c) => c.slug === b.slug)) return fail('VALIDATION_FAILED', 'Đường dẫn chuyên mục đã tồn tại', 400);
  const category: ArticleCategoryRecord = { categoryId: ++state.seq, name, slug: b.slug as string, isHidden: false };
  state.categories.push(category);
  save(state);
  return ok(categoryOf(category, state), 201, 'Đã tạo chuyên mục');
};

/** Chỉ đổi tên và ẩn / hiện; `slug` trong body bị bỏ qua (A1: slug chuyên mục không đổi). */
const updateCategory: MockHandler = ({ body, params, account }) => {
  const badId = typeError(params, ['categoryId']);
  if (badId) return badId;
  const b = readBody(body, { text: ['name'], bool: ['isHidden'] });
  if (!b) return malformed();
  const bad = invalid([
    ['name', 'Tên chuyên mục tối đa 100 ký tự', tooLong(b.name, 100)],
    ['name', 'Tên chuyên mục không được để trống', blank(b.name)],
  ]);
  if (bad) return bad;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const category = state.categories.find((c) => c.categoryId === Number(params.categoryId));
  if (!category) return notFound('chuyên mục', params.categoryId);
  if (has(b.name)) {
    const name = norm(b.name as string);
    if (state.categories.some((c) => c.categoryId !== category.categoryId && c.name === name)) {
      return fail('VALIDATION_FAILED', 'Tên chuyên mục đã tồn tại', 400);
    }
    category.name = name;
  }
  if (typeof b.isHidden === 'boolean') category.isHidden = b.isHidden;
  save(state);
  return ok(categoryOf(category, state), 200, 'Đã cập nhật chuyên mục');
};

/** Còn bài ở mọi trạng thái thì không xóa, chỉ ẩn (BR-BV-04). */
const deleteCategory: MockHandler = ({ params, account }) => {
  const badId = typeError(params, ['categoryId']);
  if (badId) return badId;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const category = state.categories.find((c) => c.categoryId === Number(params.categoryId));
  if (!category) return notFound('chuyên mục', params.categoryId);
  const count = state.articles.filter((a) => a.categoryId === category.categoryId).length;
  if (count > 0) return ruleFail('BR-BV-04', `Chuyên mục còn ${count} bài viết, chỉ ẩn được`);
  state.categories = state.categories.filter((c) => c !== category);
  save(state);
  return noContent();
};

// ─── Quản trị: bài viết (#7–13) ───

const articleOf = (a: ArticleRecord): Article => ({
  articleId: a.articleId,
  categoryId: a.categoryId,
  title: a.title,
  slug: a.slug,
  coverImageUrl: a.coverImageUrl,
  summary: a.summary,
  content: a.content,
  authorDisplayName: a.authorDisplayName,
  status: a.status,
  firstPublishedAt: a.firstPublishedAt,
  createdAt: a.createdAt,
  updatedAt: a.updatedAt,
});

const summaryOf = (a: ArticleRecord): ArticleSummary => ({
  articleId: a.articleId,
  categoryId: a.categoryId,
  title: a.title,
  slug: a.slug,
  status: a.status,
  firstPublishedAt: a.firstPublishedAt,
  updatedAt: a.updatedAt,
});

/** Tìm bài theo `:articleId` (đã qua `typeError`). */
const findArticle = (state: ContentState, params: Record<string, string>) => state.articles.find((a) => a.articleId === Number(params.articleId));

/** Mọi trạng thái, sắp `updatedAt` giảm dần rồi id giảm dần; `q` chỉ tìm trong tiêu đề; chuyên mục không có thì trang rỗng, không 404. */
const listArticles: MockHandler = ({ query, account }) => {
  const status = query.status?.trim();
  const bad =
    (status && !STATUSES.includes(status) ? fail('MALFORMED_REQUEST', "Tham số 'status' sai kiểu dữ liệu", 400) : null) ??
    typeError(query, ['categoryId', 'page', 'size']) ??
    invalid([
      ['categoryId', 'categoryId phải là số dương', query.categoryId !== undefined && Number(query.categoryId) <= 0],
      ['q', 'Từ khóa tối đa 100 ký tự', tooLong(query.q, 100)],
    ]);
  if (bad) return bad;
  const denial = guard(account);
  if (denial) return denial;
  const q = query.q ? norm(query.q).toLowerCase() : '';
  const found = load()
    .articles.filter(
      (a) =>
        (!status || a.status === status) &&
        (query.categoryId === undefined || a.categoryId === Number(query.categoryId)) &&
        (!q || a.title.toLowerCase().includes(q)),
    )
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || b.articleId - a.articleId);
  return ok(pageOf(found.map(summaryOf), clampPaging(query)));
};

const getArticle: MockHandler = ({ params, account }) => {
  const badId = typeError(params, ['articleId']);
  if (badId) return badId;
  const denial = guard(account);
  if (denial) return denial;
  const article = findArticle(load(), params);
  return article ? ok(articleOf(article)) : notFound('bài viết', params.articleId);
};

/** Bài mới luôn DRAFT, chưa có `firstPublishedAt`. Ảnh bìa gửi rỗng là sai (chỉ PATCH mới dùng "" để xóa). */
const createArticle: MockHandler = ({ body, account }) => {
  const b = readBody(body, { text: ['title', 'slug', 'coverImageUrl', 'summary', 'content', 'authorDisplayName'], int: ['categoryId'] });
  if (!b) return malformed();
  const bad = invalid(createArticleChecks(b));
  if (bad) return bad;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const content = cleanContent(b.content as string);
  if (content === null) return emptyContent();
  const categoryId = b.categoryId as number;
  if (!state.categories.some((c) => c.categoryId === categoryId)) return notFound('chuyên mục', categoryId);
  if (state.articles.some((a) => a.slug === b.slug)) return ruleFail('BR-BV-01', 'Đường dẫn bài viết đã tồn tại');
  const now = nowIso();
  const article: ArticleRecord = {
    articleId: ++state.seq,
    categoryId,
    title: norm(b.title as string),
    slug: b.slug as string,
    coverImageUrl: blankToNull(b.coverImageUrl as string | undefined),
    summary: has(b.summary) ? blankToNull(norm(b.summary as string)) : null,
    content,
    authorDisplayName: norm(b.authorDisplayName as string),
    status: 'DRAFT',
    firstPublishedAt: null,
    createdAt: now,
    updatedAt: now,
  };
  state.articles.push(article);
  save(state);
  return ok(articleOf(article), 201, 'Đã tạo bài viết');
};

/**
 * Trường vắng hoặc null giữ nguyên; `""` xóa ảnh bìa / tóm tắt. Slug chỉ kiểm khi khác slug hiện tại (gửi lại đúng slug luôn hợp lệ,
 * kể cả sau khi xuất bản). Mọi kiểm tra xong mới ghi, và `updatedAt` chỉ đổi khi có thay đổi thật.
 */
const updateArticle: MockHandler = ({ body, params, account }) => {
  const badId = typeError(params, ['articleId']);
  if (badId) return badId;
  const b = readBody(body, { text: ['title', 'slug', 'coverImageUrl', 'summary', 'content', 'authorDisplayName'], int: ['categoryId'] });
  if (!b) return malformed();
  const bad = invalid(updateArticleChecks(b));
  if (bad) return bad;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  if (has(b.categoryId) && !state.categories.some((c) => c.categoryId === b.categoryId)) return notFound('chuyên mục', b.categoryId as number);
  const article = findArticle(state, params);
  if (!article) return notFound('bài viết', params.articleId);
  if (has(b.slug) && b.slug !== article.slug) {
    if (article.firstPublishedAt) return ruleFail('BR-BV-01', 'Không đổi được đường dẫn sau lần xuất bản đầu tiên');
    if (state.articles.some((a) => a.slug === b.slug)) return ruleFail('BR-BV-01', 'Đường dẫn bài viết đã tồn tại');
  }
  const content = has(b.content) ? cleanContent(b.content as string) : undefined;
  if (content === null) return emptyContent();

  const before = JSON.stringify(article);
  if (has(b.slug)) article.slug = b.slug as string;
  if (has(b.categoryId)) article.categoryId = b.categoryId as number;
  if (has(b.title)) article.title = norm(b.title as string);
  if (has(b.coverImageUrl)) article.coverImageUrl = blankToNull(b.coverImageUrl as string);
  if (has(b.summary)) article.summary = blankToNull(norm(b.summary as string));
  if (content !== undefined) article.content = content;
  if (has(b.authorDisplayName)) article.authorDisplayName = norm(b.authorDisplayName as string);
  if (JSON.stringify(article) !== before) article.updatedAt = nowIso();
  save(state);
  return ok(articleOf(article), 200, 'Đã cập nhật bài viết');
};

/** DRAFT / HIDDEN → PUBLISHED, hoặc PUBLISHED → HIDDEN; sai trạng thái là 409. `firstPublishedAt` ghi đúng một lần (BR-BV-02). */
const transition =
  (to: 'PUBLISHED' | 'HIDDEN'): MockHandler =>
  ({ params, account }) => {
    const badId = typeError(params, ['articleId']);
    if (badId) return badId;
    const denial = guard(account);
    if (denial) return denial;
    const state = load();
    const article = findArticle(state, params);
    if (!article) return notFound('bài viết', params.articleId);
    const allowed = to === 'PUBLISHED' ? article.status !== 'PUBLISHED' : article.status === 'PUBLISHED';
    if (!allowed) return fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái Article từ ${article.status} sang ${to}`, 409);
    const now = nowIso();
    article.status = to;
    if (to === 'PUBLISHED') article.firstPublishedAt ??= now;
    article.updatedAt = now;
    save(state);
    return ok(articleOf(article), 200, to === 'PUBLISHED' ? 'Đã xuất bản bài viết' : 'Đã gỡ bài viết');
  };

/** Bài đã từng xuất bản (kể cả đang ẩn) không xóa được, chỉ ẩn (BR-BV-02). */
const deleteArticle: MockHandler = ({ params, account }) => {
  const badId = typeError(params, ['articleId']);
  if (badId) return badId;
  const denial = guard(account);
  if (denial) return denial;
  const state = load();
  const article = findArticle(state, params);
  if (!article) return notFound('bài viết', params.articleId);
  if (article.firstPublishedAt) return ruleFail('BR-BV-02', 'Bài viết đã từng xuất bản, chỉ ẩn được');
  state.articles = state.articles.filter((a) => a !== article);
  save(state);
  return noContent();
};

export const contentRoutes: Record<string, MockHandler> = {
  'GET /api/public/article-categories': listPublicArticleCategories,
  'GET /api/public/articles': listPublicArticles,
  'GET /api/public/articles/:slug': getPublicArticle,
  'GET /api/article-categories': listCategories,
  'POST /api/article-categories': createCategory,
  'PATCH /api/article-categories/:categoryId': updateCategory,
  'DELETE /api/article-categories/:categoryId': deleteCategory,
  'GET /api/articles': listArticles,
  'POST /api/articles': createArticle,
  'GET /api/articles/:articleId': getArticle,
  'PATCH /api/articles/:articleId': updateArticle,
  'POST /api/articles/:articleId/publish': transition('PUBLISHED'),
  'POST /api/articles/:articleId/hide': transition('HIDDEN'),
  'DELETE /api/articles/:articleId': deleteArticle,
};
