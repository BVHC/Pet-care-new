import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import type { Article, ArticleCategory, ArticleSummary } from '../../types/content';
import type { PublicArticle, PublicArticleCard, PublicArticleCategory } from '../../types/public-site';
import { CONTENT_STATE_KEY, seedContent } from './content.seed';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (content.seed.ts): 4 chuyên mục, trong đó "Lưu trú & dịch vụ" ẩn nhưng có 1 bài PUBLISHED; 10 bài:
// 8 PUBLISHED, 1 DRAFT (meo-cao-sofa…), 1 HIDDEN (cho-so-phao-hoa…).
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
const articles = async (params: Record<string, unknown> = {}) =>
  (await client.get('/api/public/articles', { params })).data.data as Page<PublicArticleCard>;
const slugs = (list: { slug: string }[]) => list.map((a) => a.slug);

const DRAFT = 'meo-cao-sofa-5-cach-ngan-chan';
const HIDDEN = 'cho-so-phao-hoa-4-buoc-binh-tinh';
const IN_HIDDEN_CATEGORY = 'chon-khach-san-thu-cung-uy-tin';
const NEWEST = 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung';

/** Ghi đè trạng thái mock như T47 sẽ làm khi xuất bản / gỡ bài. */
function edit(change: (state: ReturnType<typeof seedContent>) => void) {
  const state = seedContent();
  change(state);
  localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(state));
}

describe('bài viết công khai (public, UC17; BR-BV-03, BR-CK-01)', () => {
  it('không cần đăng nhập; chỉ bài PUBLISHED, mới xuất bản trước', async () => {
    const res = await client.get('/api/public/articles');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 8, totalPages: 1 });
    const list = res.data.data.content as PublicArticleCard[];
    expect(list).toHaveLength(8);
    expect(slugs(list)).not.toContain(DRAFT);
    expect(slugs(list)).not.toContain(HIDDEN);
    expect(list[0].slug).toBe(NEWEST);
    const times = list.map((a) => Date.parse(a.publishedAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    expect(new Set(times).size).toBe(8);
  });

  it('thẻ bài chỉ có các trường của PublicArticleCard (không content, status); publishedAt có offset +07:00', async () => {
    const card = (await articles()).content[0];
    expect(Object.keys(card).sort()).toEqual(
      ['articleId', 'authorDisplayName', 'categoryName', 'categorySlug', 'coverImageUrl', 'publishedAt', 'slug', 'summary', 'title'].sort(),
    );
    expect(card.publishedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+07:00$/);
    expect(card).toMatchObject({ title: expect.any(String), categorySlug: expect.any(String), authorDisplayName: 'Dr. Minh Thu' });
  });

  it('có bài không ảnh bìa (coverImageUrl null) và ảnh bìa là đường dẫn nội bộ /imgs/ của mock', async () => {
    const list = (await articles()).content;
    expect(list.some((a) => a.coverImageUrl == null)).toBe(true);
    expect(list.filter((a) => a.coverImageUrl != null).every((a) => a.coverImageUrl!.startsWith('/imgs/'))).toBe(true);
  });

  describe('lọc theo chuyên mục (categorySlug)', () => {
    it('chuyên mục thường', async () => {
      const res = await articles({ categorySlug: 'suc-khoe-thu-y' });
      expect(res.totalElements).toBe(3);
      expect(res.content.every((a) => a.categorySlug === 'suc-khoe-thu-y')).toBe(true);
    });

    it('chuyên mục ẩn không có trong danh sách chuyên mục nhưng vẫn lọc được bài đã xuất bản (BR-BV-04)', async () => {
      const categories = (await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[];
      expect(categories.map((c) => c.slug)).not.toContain('luu-tru-dich-vu');
      expect(slugs((await articles({ categorySlug: 'luu-tru-dich-vu' })).content)).toEqual([IN_HIDDEN_CATEGORY]);
    });

    it('slug chuyên mục không có thì trang rỗng 200, không phải 404', async () => {
      const res = await client.get('/api/public/articles', { params: { categorySlug: 'khong-co' } });
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
    });

    it('chuyên mục chỉ có bài DRAFT / HIDDEN thì lọc ra trang rỗng', async () => {
      edit((s) => {
        for (const a of s.articles) if (a.categoryId === 3) a.status = 'HIDDEN';
      });
      expect((await articles({ categorySlug: 'cham-soc-hang-ngay' })).content).toEqual([]);
    });
  });

  describe('phân trang: page từ 0, size 1–100 (mặc định 20), ngoài khoảng thì kẹp, không báo lỗi', () => {
    it('chia trang giữ thứ tự mới nhất trước', async () => {
      const all = slugs((await articles()).content);
      const first = await articles({ size: 3 });
      expect(first).toMatchObject({ page: 0, size: 3, totalElements: 8, totalPages: 3 });
      expect(slugs(first.content)).toEqual(all.slice(0, 3));
      expect(slugs((await articles({ page: 2, size: 3 })).content)).toEqual(all.slice(6));
      expect(await articles({ page: 9, size: 3 })).toMatchObject({ content: [], page: 9, totalElements: 8 });
    });

    it.each([
      [{ page: -1 }, 0, 20],
      [{ size: 0 }, 0, 1],
      [{ size: -5 }, 0, 1],
      [{ size: 101 }, 0, 100],
    ])('kẹp %j thành page %i, size %i', async (params, page, size) => {
      const res = await articles(params);
      expect(res).toMatchObject({ page, size });
      expect(res.content.length).toBe(Math.min(size, 8));
    });

    it('page / size không phải số nguyên thì 400 MALFORMED_REQUEST', async () => {
      expect((await failure(client.get('/api/public/articles', { params: { page: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
      expect((await failure(client.get('/api/public/articles', { params: { size: 'x' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });

  it('đọc trạng thái từ state, không theo hằng seed: gỡ một bài thì biến khỏi danh sách, đăng bài nháp thì hiện lên đầu', async () => {
    edit((s) => {
      s.articles.find((a) => a.slug === NEWEST)!.status = 'HIDDEN';
      const draft = s.articles.find((a) => a.slug === DRAFT)!;
      draft.status = 'PUBLISHED';
      draft.firstPublishedAt = '2026-10-20T08:00:00+07:00';
    });
    const list = slugs((await articles()).content);
    expect(list).not.toContain(NEWEST);
    expect(list[0]).toBe(DRAFT);
    expect(list).toHaveLength(8);
  });
});

describe('đọc bài viết công khai (public, UC17; BR-BV-03)', () => {
  it('bài PUBLISHED: 200, đúng các trường của PublicArticle, content là HTML an toàn (ADR-0071)', async () => {
    const res = await client.get(`/api/public/articles/${NEWEST}`);
    const article = res.data.data as PublicArticle;
    expect(Object.keys(article).sort()).toEqual(
      ['articleId', 'authorDisplayName', 'categoryName', 'categorySlug', 'content', 'coverImageUrl', 'publishedAt', 'slug', 'summary', 'title'].sort(),
    );
    expect(article).toMatchObject({ slug: NEWEST, categorySlug: 'kien-thuc-nuoi-thu-cung', categoryName: 'Kiến thức nuôi thú cưng' });
    expect(article.content).toContain('<p>');
    expect(article.content).not.toMatch(/<script|<iframe|\son\w+\s*=|javascript:/i);
  });

  it('mọi bài mẫu chỉ dùng thẻ HTML trong danh sách cho phép, không có link / ảnh ngoài https', async () => {
    for (const slug of slugs((await articles()).content)) {
      const { content } = (await client.get(`/api/public/articles/${slug}`)).data.data as PublicArticle;
      const tags = [...content.matchAll(/<\/?([a-z0-9]+)/gi)].map((m) => m[1].toLowerCase());
      expect(tags.length).toBeGreaterThan(0);
      expect(tags.every((t) => ['p', 'h2', 'h3', 'ul', 'ol', 'li', 'strong', 'em', 'br'].includes(t))).toBe(true);
      expect(content).not.toMatch(/(href|src)\s*=\s*["']?(?!https:)/i);
    }
  });

  it('bài trong chuyên mục ẩn vẫn đọc được theo đường dẫn (BR-BV-04)', async () => {
    const res = await client.get(`/api/public/articles/${IN_HIDDEN_CATEGORY}`);
    expect(res.data.data).toMatchObject({ categorySlug: 'luu-tru-dich-vu' });
  });

  it('bài DRAFT, bài HIDDEN, slug lạ và slug sai định dạng cùng một 404 (không lộ bài tồn tại)', async () => {
    const shape = async (slug: string) => {
      const res = await failure(client.get(`/api/public/articles/${encodeURIComponent(slug)}`));
      return { status: res.status, errorCode: res.data.errorCode, message: String(res.data.message).replace(slug, '<slug>') };
    };
    const unknown = await shape('khong-co-bai-nay');
    expect(unknown).toMatchObject({ status: 404, errorCode: 'RESOURCE_NOT_FOUND' });
    expect(await shape(DRAFT)).toEqual(unknown);
    expect(await shape(HIDDEN)).toEqual(unknown);
    expect(await shape('Sai_Dinh_Dang!')).toEqual(unknown);
  });

  it('bài PUBLISHED bị gỡ (HIDDEN) thì 404 ngay', async () => {
    edit((s) => {
      s.articles.find((a) => a.slug === NEWEST)!.status = 'HIDDEN';
    });
    expect((await failure(client.get(`/api/public/articles/${NEWEST}`))).status).toBe(404);
  });
});

describe('chuyên mục bài viết công khai (public, UC17; BR-BV-04)', () => {
  it('bỏ chuyên mục ẩn, chỉ ba trường, theo tên', async () => {
    const res = await client.get('/api/public/article-categories');
    const list = res.data.data as PublicArticleCategory[];
    expect(list.map((c) => c.slug).sort()).toEqual(['cham-soc-hang-ngay', 'kien-thuc-nuoi-thu-cung', 'suc-khoe-thu-y']);
    expect(Object.keys(list[0]).sort()).toEqual(['categoryId', 'name', 'slug']);
    const names = list.map((c) => c.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'vi')));
  });

  it('chuyên mục vừa bị ẩn thì biến khỏi danh sách', async () => {
    edit((s) => {
      s.categories.find((c) => c.slug === 'suc-khoe-thu-y')!.isHidden = true;
    });
    const list = (await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[];
    expect(list.map((c) => c.slug)).not.toContain('suc-khoe-thu-y');
  });
});

describe('dữ liệu mẫu và quyền gọi', () => {
  it('slug bài / chuyên mục duy nhất, đúng định dạng BE; chỉ DRAFT chưa có firstPublishedAt; mọi bài trỏ tới chuyên mục có thật', () => {
    const { articles, categories } = seedContent();
    const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
    for (const slugList of [articles.map((a) => a.slug), categories.map((c) => c.slug)]) {
      expect(new Set(slugList).size).toBe(slugList.length);
      expect(slugList.every((s) => SLUG.test(s))).toBe(true);
    }
    for (const a of articles) {
      expect(a.firstPublishedAt !== null).toBe(a.status !== 'DRAFT');
      expect(categories.some((c) => c.categoryId === a.categoryId)).toBe(true);
    }
  });

  it('cả bài DRAFT / HIDDEN (T47 sẽ đăng / hiện lại) chỉ dùng thẻ cho phép, không link / ảnh ngoài https', () => {
    for (const { slug, content } of seedContent().articles) {
      const tags = [...content.matchAll(/<\/?([a-z0-9]+)/gi)].map((m) => m[1].toLowerCase());
      expect(tags.every((t) => ['p', 'h2', 'h3', 'ul', 'ol', 'li', 'strong', 'em', 'br'].includes(t)), slug).toBe(true);
      expect(content, slug).not.toMatch(/(href|src)\s*=\s*["']?(?!https:)/i);
    }
  });

  it('token gửi kèm (kể cả sai) bị bỏ qua ở path công khai (ADR-0005)', async () => {
    const res = await client.get('/api/public/articles', { headers: { Authorization: 'Bearer het-han' } });
    expect(res.status).toBe(200);
  });
});

// ─── Quản trị chuyên mục và bài viết (UC81, content-v1 #3–13; BR-BV-01…04) ───
// Luật, thông điệp và thứ tự lỗi chép từ BE (ArticleService, ArticleCategoryService, DTO Bean Validation), xem đầu content.mock.ts.

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}

const send = (method: string, url: string, body?: object, config?: object) => client.request({ method, url, data: body, ...config });

describe('quản trị chuyên mục và bài viết (UC81)', () => {
  let auth: Awaited<ReturnType<typeof as>>;
  beforeEach(async () => {
    auth = await as('chuoi@petcare.vn');
  });
  afterEach(() => vi.useRealTimers());

  const get = (url: string, params?: object) => client.get(url, { params, ...auth });
  const post = (url: string, body?: object) => client.post(url, body, auth);
  const patch = (url: string, body: object | null) => client.patch(url, body, auth);
  const del = (url: string) => client.delete(url, auth);
  const dataOf = <T>(res: AxiosResponse) => res.data.data as T;
  const summaries = async (params: Record<string, unknown> = {}) => dataOf<Page<ArticleSummary>>(await get('/api/articles', { size: 100, ...params }));
  const categories = async () => dataOf<ArticleCategory[]>(await get('/api/article-categories'));
  const article = async (id: number) => dataOf<Article>(await get(`/api/articles/${id}`));
  const ids = (list: { articleId: number }[]) => list.map((a) => a.articleId);

  const NEW_ARTICLE = { categoryId: 1, title: 'Bài thử', slug: 'bai-thu', content: '<p>Nội dung</p>', authorDisplayName: 'Tác giả' };
  const create = async (change: Record<string, unknown> = {}) => dataOf<Article>(await post('/api/articles', { ...NEW_ARTICLE, ...change }));
  /** Chỉ giả `Date` (giữ setTimeout thật cho độ trễ của adapter). 2026-10-26 10:00 giờ Việt Nam, sau mọi ngày trong dữ liệu mẫu. */
  const NOW_VN = '2026-10-26T10:00:00+07:00';
  const freezeAt = (instant: string) => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(instant));
  };
  const FROZEN = '2026-10-26T03:00:00Z';
  const FIRST_PUBLISHED_OF_HIDDEN = '2026-07-01T08:00:00+07:00';
  const SLUG_RULE = 'slug: Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang';

  describe('quyền và thứ tự kiểm lỗi: 401 → 400 → 403 → 404 → 400 rule → 409', () => {
    const ROUTES: [method: string, url: string, body?: object][] = [
      ['get', '/api/article-categories'],
      ['post', '/api/article-categories', { name: 'Mới', slug: 'moi' }],
      ['patch', '/api/article-categories/1', { name: 'Đổi' }],
      ['delete', '/api/article-categories/1'],
      ['get', '/api/articles'],
      ['post', '/api/articles', NEW_ARTICLE],
      ['get', '/api/articles/1'],
      ['patch', '/api/articles/1', { title: 'Đổi' }],
      ['post', '/api/articles/1/publish'],
      ['post', '/api/articles/1/hide'],
      ['delete', '/api/articles/6'],
    ];

    it.each(ROUTES)('%s %s: chưa đăng nhập → 401 UNAUTHENTICATED; người quản lý gọi được (route có thật, không 404)', async (method, url, body) => {
      const anonymous = await failure(send(method, url, body));
      expect([anonymous.status, anonymous.data.errorCode]).toEqual([401, 'UNAUTHENTICATED']);
      const manager = await send(method, url, body, auth).catch((e) => e.response as AxiosResponse);
      expect(manager.status).not.toBe(404);
    });

    it.each([
      ['ADMIN kỹ thuật', 'admin@petcare.vn'],
      ['BRANCH_MANAGER', 'manager@store1.vn'],
      ['RECEPTIONIST', 'reception@store1.vn'],
      ['VET', 'doctor@store1.vn'],
      ['CARETAKER', 'caretaker@store1.vn'],
      ['CUSTOMER', 'khach@petcare.vn'],
    ])('%s: cả 11 route đều 403 ACCESS_DENIED (kể cả đọc) và không ghi gì', async (_role, email) => {
      const who = await as(email);
      for (const [method, url, body] of ROUTES) {
        const res = await failure(send(method, url, body, who));
        expect([method, url, res.status, res.data.errorCode]).toEqual([method, url, 403, 'ACCESS_DENIED']);
      }
      expect((await summaries()).totalElements).toBe(10);
      expect(await categories()).toHaveLength(4);
      expect((await article(1)).status).toBe('PUBLISHED');
      expect((await article(6)).status).toBe('DRAFT');
    });

    it('400 (body, query, path) đến trước 403', async () => {
      const desk = await as('reception@store1.vn');
      const replies = [
        await failure(client.post('/api/articles', {}, desk)),
        await failure(client.post('/api/article-categories', { name: '', slug: 'x' }, desk)),
        await failure(client.patch('/api/articles/1', { slug: 'Sai Slug' }, desk)),
        await failure(client.get('/api/articles', { params: { status: 'NOPE' }, ...desk })),
        await failure(client.get('/api/articles', { params: { q: 'a'.repeat(101) }, ...desk })),
        await failure(client.get('/api/articles/abc', desk)),
        await failure(client.delete('/api/article-categories/1.5', desk)),
      ];
      expect(replies.map((r) => [r.status, r.data.errorCode])).toEqual([
        [400, 'VALIDATION_FAILED'],
        [400, 'VALIDATION_FAILED'],
        [400, 'VALIDATION_FAILED'],
        [400, 'MALFORMED_REQUEST'],
        [400, 'VALIDATION_FAILED'],
        [400, 'MALFORMED_REQUEST'],
        [400, 'MALFORMED_REQUEST'],
      ]);
    });

    it('403 đến trước 404', async () => {
      const desk = await as('reception@store1.vn');
      const missing: [string, string, object?][] = [
        ['get', '/api/articles/9999'],
        ['patch', '/api/articles/9999', { title: 'x' }],
        ['post', '/api/articles/9999/publish'],
        ['post', '/api/articles/9999/hide'],
        ['delete', '/api/articles/9999'],
        ['patch', '/api/article-categories/9999', { name: 'x' }],
        ['delete', '/api/article-categories/9999'],
      ];
      for (const [method, url, body] of missing) expect((await failure(send(method, url, body, desk))).status, `${method} ${url}`).toBe(403);
    });

    it('POST /articles: nội dung trống sau lọc đến trước chuyên mục lạ (404), chuyên mục lạ đến trước slug trùng', async () => {
      const taken = seedContent().articles[0].slug;
      const empty = await failure(post('/api/articles', { ...NEW_ARTICLE, categoryId: 999, slug: taken, content: '<script>x</script>' }));
      expect([empty.status, ruleIdOf(empty.data.message)]).toEqual([400, 'BR-BV-01']);
      expect(empty.data.message).toBe('Nội dung bài viết trống sau khi lọc (BR-BV-01)');

      const unknownCategory = await failure(post('/api/articles', { ...NEW_ARTICLE, categoryId: 999, slug: taken }));
      expect([unknownCategory.status, unknownCategory.data.errorCode]).toEqual([404, 'RESOURCE_NOT_FOUND']);

      const duplicate = await failure(post('/api/articles', { ...NEW_ARTICLE, slug: taken }));
      expect(duplicate.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Đường dẫn bài viết đã tồn tại (BR-BV-01)' });
    });

    it('PATCH /articles: chuyên mục lạ đến trước bài lạ; khóa slug đến trước slug trùng; slug trùng đến trước nội dung trống', async () => {
      const [first, second] = seedContent().articles;
      const bothUnknown = await failure(patch('/api/articles/9999', { categoryId: 999 }));
      expect([bothUnknown.status, bothUnknown.data.message]).toEqual([404, 'Không tìm thấy chuyên mục #999']);

      const locked = await failure(patch('/api/articles/1', { slug: second.slug, content: '<script>x</script>' }));
      expect(locked.data.message).toBe('Không đổi được đường dẫn sau lần xuất bản đầu tiên (BR-BV-01)');

      const duplicate = await failure(patch('/api/articles/6', { slug: first.slug, content: '<script>x</script>' }));
      expect(duplicate.data.message).toBe('Đường dẫn bài viết đã tồn tại (BR-BV-01)');

      const empty = await failure(patch('/api/articles/6', { content: '<script>x</script>' }));
      expect(empty.data.message).toBe('Nội dung bài viết trống sau khi lọc (BR-BV-01)');
    });

    it('404 đến trước 409 và lỗi nghiệp vụ', async () => {
      for (const [method, url] of [['post', '/api/articles/9999/publish'], ['post', '/api/articles/9999/hide'], ['delete', '/api/articles/9999']]) {
        expect((await failure(send(method, url, undefined, auth))).status, `${method} ${url}`).toBe(404);
      }
    });
  });

  describe('chuyên mục (#3–6; BR-BV-04)', () => {
    it('GET: cả chuyên mục ẩn, theo tên, đủ 5 trường; articleCount đếm bài ở mọi trạng thái', async () => {
      const res = await get('/api/article-categories');
      expect(res.status).toBe(200);
      const list = dataOf<ArticleCategory[]>(res);
      expect(list.map((c) => [c.categoryId, c.articleCount, c.isHidden])).toEqual([
        [3, 2, false],
        [1, 4, false],
        [4, 1, true],
        [2, 3, false],
      ]);
      expect(Object.keys(list[0]).sort()).toEqual(['articleCount', 'categoryId', 'isHidden', 'name', 'slug']);
    });

    it('POST: 201, chuyên mục mới không ẩn và chưa có bài; tên được chuẩn hóa NFC và cắt khoảng trắng', async () => {
      const res = await post('/api/article-categories', { name: '  Dinh dưỡng '.normalize('NFD'), slug: 'dinh-duong' });
      expect(res.status).toBe(201);
      expect(dataOf<ArticleCategory>(res)).toEqual({ categoryId: 101, name: 'Dinh dưỡng', slug: 'dinh-duong', isHidden: false, articleCount: 0 });
      expect((await categories()).map((c) => c.slug)).toContain('dinh-duong');
    });

    it.each([
      ['thiếu tên', { slug: 'a' }, 'name: Vui lòng nhập tên chuyên mục'],
      ['tên toàn khoảng trắng', { name: '   ', slug: 'a' }, 'name: Vui lòng nhập tên chuyên mục'],
      ['tên quá 100 ký tự', { name: 'n'.repeat(101), slug: 'a' }, 'name: Tên chuyên mục tối đa 100 ký tự'],
      ['thiếu slug', { name: 'A' }, 'slug: Vui lòng nhập đường dẫn'],
      ['slug quá 120 ký tự', { name: 'A', slug: 'a'.repeat(121) }, 'slug: Đường dẫn tối đa 120 ký tự'],
      ['slug viết hoa', { name: 'A', slug: 'Tin-Tuc' }, SLUG_RULE],
      ['slug có khoảng trắng', { name: 'A', slug: 'tin tuc' }, SLUG_RULE],
      ['slug hai gạch liền', { name: 'A', slug: 'tin--tuc' }, SLUG_RULE],
      ['slug mở đầu bằng gạch', { name: 'A', slug: '-tin' }, SLUG_RULE],
      ['slug có dấu', { name: 'A', slug: 'tín' }, SLUG_RULE],
      ['nhiều lỗi gộp bằng "; "', {}, 'name: Vui lòng nhập tên chuyên mục; slug: Vui lòng nhập đường dẫn'],
    ])('POST: %s → 400 VALIDATION_FAILED', async (_name, body, message) => {
      const res = await failure(post('/api/article-categories', body));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message });
      expect(await categories()).toHaveLength(4);
    });

    it('POST: body không phải object → 400 MALFORMED_REQUEST', async () => {
      expect((await failure(client.post('/api/article-categories', null, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
      expect((await failure(client.post('/api/article-categories', [1], auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('POST: tên hoặc slug trùng → 400 VALIDATION_FAILED (tên kiểm trước slug), không ghi gì', async () => {
      const name = await failure(post('/api/article-categories', { name: 'Sức khỏe thú y', slug: 'moi' }));
      expect(name.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Tên chuyên mục đã tồn tại' });
      const slug = await failure(post('/api/article-categories', { name: 'Mới', slug: 'suc-khoe-thu-y' }));
      expect(slug.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Đường dẫn chuyên mục đã tồn tại' });
      const both = await failure(post('/api/article-categories', { name: 'Sức khỏe thú y', slug: 'suc-khoe-thu-y' }));
      expect(both.data.message).toBe('Tên chuyên mục đã tồn tại');
      const nfd = await failure(post('/api/article-categories', { name: ' Sức khỏe thú y '.normalize('NFD'), slug: 'moi' }));
      expect(nfd.data.message).toBe('Tên chuyên mục đã tồn tại');
      expect(await categories()).toHaveLength(4);
    });

    it('POST: tên chỉ khác hoa / thường thì được (ràng buộc UNIQUE phân biệt hoa thường)', async () => {
      expect((await post('/api/article-categories', { name: 'sức khỏe thú y', slug: 'suc-khoe-thu-y-2' })).status).toBe(201);
    });

    it('PATCH: đổi tên, ẩn / hiện; slug trong body bị bỏ qua (A1); trả đủ articleCount', async () => {
      const hidden = dataOf<ArticleCategory>(await patch('/api/article-categories/1', { isHidden: true, slug: 'doi-slug' }));
      expect(hidden).toEqual({ categoryId: 1, name: 'Kiến thức nuôi thú cưng', slug: 'kien-thuc-nuoi-thu-cung', isHidden: true, articleCount: 4 });
      const renamed = dataOf<ArticleCategory>(await patch('/api/article-categories/1', { name: '  Kiến thức ', isHidden: false }));
      expect(renamed).toMatchObject({ name: 'Kiến thức', isHidden: false, slug: 'kien-thuc-nuoi-thu-cung', articleCount: 4 });
      expect(dataOf<ArticleCategory>(await patch('/api/article-categories/1', {}))).toMatchObject({ name: 'Kiến thức', isHidden: false });
    });

    it('PATCH: tên trùng chuyên mục khác → 400; trùng chính nó thì được; tên trống hoặc quá dài → 400; không ghi gì khi lỗi', async () => {
      const dup = await failure(patch('/api/article-categories/1', { name: 'Sức khỏe thú y', isHidden: true }));
      expect(dup.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Tên chuyên mục đã tồn tại' });
      expect((await categories()).find((c) => c.categoryId === 1)).toMatchObject({ name: 'Kiến thức nuôi thú cưng', isHidden: false });
      expect((await patch('/api/article-categories/1', { name: 'Kiến thức nuôi thú cưng' })).status).toBe(200);
      expect((await failure(patch('/api/article-categories/1', { name: '   ' }))).data.message).toBe('name: Tên chuyên mục không được để trống');
      expect((await failure(patch('/api/article-categories/1', { name: 'n'.repeat(101) }))).data.message).toBe('name: Tên chuyên mục tối đa 100 ký tự');
    });

    it('PATCH: id lạ → 404; isHidden sai kiểu → 400 MALFORMED_REQUEST', async () => {
      const missing = await failure(patch('/api/article-categories/9999', { isHidden: true }));
      expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy chuyên mục #9999']);
      expect((await failure(patch('/api/article-categories/1', { isHidden: 'có' }))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('DELETE: chuyên mục rỗng → 204 và biến mất; xóa lại → 404', async () => {
      const created = dataOf<ArticleCategory>(await post('/api/article-categories', { name: 'Tạm', slug: 'tam' }));
      const res = await del(`/api/article-categories/${created.categoryId}`);
      expect([res.status, res.data]).toEqual([204, '']);
      expect((await categories()).map((c) => c.categoryId)).not.toContain(created.categoryId);
      expect((await failure(del(`/api/article-categories/${created.categoryId}`))).status).toBe(404);
    });

    it('DELETE: còn bài (kể cả bài nháp, chuyên mục ẩn) → 400 BR-BV-04 kèm số bài, không xóa', async () => {
      const full = await failure(del('/api/article-categories/1'));
      expect(full.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chuyên mục còn 4 bài viết, chỉ ẩn được (BR-BV-04)' });
      expect((await failure(del('/api/article-categories/4'))).data.message).toBe('Chuyên mục còn 1 bài viết, chỉ ẩn được (BR-BV-04)');
      expect(await categories()).toHaveLength(4);
    });

    it('DELETE: sau khi xóa hết bài nháp cuối cùng thì xóa được chuyên mục; id không phải số → 400 MALFORMED_REQUEST', async () => {
      const category = dataOf<ArticleCategory>(await post('/api/article-categories', { name: 'Tạm', slug: 'tam' }));
      const draft = await create({ categoryId: category.categoryId });
      expect((await failure(del(`/api/article-categories/${category.categoryId}`))).data.message).toBe('Chuyên mục còn 1 bài viết, chỉ ẩn được (BR-BV-04)');
      await del(`/api/articles/${draft.articleId}`);
      expect((await del(`/api/article-categories/${category.categoryId}`)).status).toBe(204);
      expect((await failure(del('/api/article-categories/abc'))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('articleCount suy ra từ bài: tạo +1, chuyển chuyên mục, xóa bài nháp', async () => {
      const count = async (id: number) => (await categories()).find((c) => c.categoryId === id)!.articleCount;
      const draft = await create({ categoryId: 3 });
      expect(await count(3)).toBe(3);
      await patch(`/api/articles/${draft.articleId}`, { categoryId: 2 });
      expect([await count(3), await count(2)]).toEqual([2, 4]);
      await del(`/api/articles/${draft.articleId}`);
      expect(await count(2)).toBe(3);
    });
  });

  describe('danh sách bài (#7)', () => {
    it('mọi trạng thái, đúng 7 trường của ArticleSummary, sắp theo updatedAt giảm dần (không phải theo ngày xuất bản)', async () => {
      const res = await get('/api/articles');
      expect(res.status).toBe(200);
      const page = dataOf<Page<ArticleSummary>>(res);
      expect(page).toMatchObject({ page: 0, size: 20, totalElements: 10, totalPages: 1 });
      expect(ids(page.content)).toEqual([1, 2, 3, 8, 4, 5, 6, 7, 9, 10]);
      expect(Object.keys(page.content[0]).sort()).toEqual(['articleId', 'categoryId', 'firstPublishedAt', 'slug', 'status', 'title', 'updatedAt']);
      expect(page.content.find((a) => a.articleId === 6)).toMatchObject({ status: 'DRAFT', firstPublishedAt: null });
    });

    it('cùng updatedAt thì id lớn trước', async () => {
      const state = seedContent();
      for (const a of state.articles) a.updatedAt = '2026-08-01T08:00:00+07:00';
      localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(state));
      expect(ids((await summaries()).content)).toEqual([10, 9, 8, 7, 6, 5, 4, 3, 2, 1]);
    });

    it.each([
      ['PUBLISHED', 8],
      ['DRAFT', 1],
      ['HIDDEN', 1],
    ])('lọc status=%s', async (status, total) => {
      const page = await summaries({ status });
      expect(page.totalElements).toBe(total);
      expect(page.content.every((a) => a.status === status)).toBe(true);
    });

    it('lọc categoryId; chuyên mục không tồn tại cho trang rỗng (200, không phải 404)', async () => {
      expect(ids((await summaries({ categoryId: 1 })).content)).toEqual([1, 3, 8, 6]);
      const none = await get('/api/articles', { categoryId: 999 });
      expect(none.status).toBe(200);
      expect(dataOf<Page<ArticleSummary>>(none)).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
    });

    it('lọc kết hợp status và categoryId', async () => {
      expect(ids((await summaries({ status: 'PUBLISHED', categoryId: 1 })).content)).toEqual([1, 3]);
    });

    it('q chỉ tìm trong tiêu đề, không phân biệt hoa thường nhưng có phân biệt dấu; không tìm theo slug hay nội dung', async () => {
      expect(ids((await summaries({ q: 'mèo' })).content)).toEqual([2, 6]);
      expect(ids((await summaries({ q: 'MÈO' })).content)).toEqual([2, 6]);
      expect((await summaries({ q: 'meo' })).totalElements).toBe(0);
      expect((await summaries({ q: 'meo-bo-an' })).totalElements).toBe(0);
      expect((await summaries({ q: 'phòng khám thú y' })).totalElements).toBe(0);
      expect(ids((await summaries({ q: '  vaccine ' })).content)).toEqual([4]);
    });

    it('q chỉ toàn khoảng trắng thì bỏ qua; % và _ là ký tự thường, không phải ký tự đại diện', async () => {
      expect((await summaries({ q: '   ' })).totalElements).toBe(10);
      const promo = await create({ title: 'Giảm 50% hôm nay_nay', slug: 'giam-gia' });
      expect(ids((await summaries({ q: '50%' })).content)).toEqual([promo.articleId]);
      expect(ids((await summaries({ q: '%' })).content)).toEqual([promo.articleId]);
      expect(ids((await summaries({ q: '_' })).content)).toEqual([promo.articleId]);
    });

    it('q dài đúng 100 ký tự thì được, 101 → 400 VALIDATION_FAILED', async () => {
      expect((await get('/api/articles', { q: 'a'.repeat(100) })).status).toBe(200);
      const res = await failure(get('/api/articles', { q: 'a'.repeat(101) }));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'q: Từ khóa tối đa 100 ký tự' });
    });

    it('status sai (kể cả chữ thường), categoryId / page / size không phải số → 400 MALFORMED_REQUEST; categoryId ≤ 0 → 400 VALIDATION_FAILED', async () => {
      for (const params of [{ status: 'NOPE' }, { status: 'published' }, { categoryId: 'abc' }, { page: 'x' }, { size: '1.5' }]) {
        const res = await failure(get('/api/articles', params));
        expect([res.status, res.data.errorCode], JSON.stringify(params)).toEqual([400, 'MALFORMED_REQUEST']);
      }
      expect((await failure(get('/api/articles', { status: 'NOPE' }))).data.message).toBe("Tham số 'status' sai kiểu dữ liệu");
      for (const categoryId of [0, -1]) {
        const res = await failure(get('/api/articles', { categoryId }));
        expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'categoryId: categoryId phải là số dương' });
      }
    });

    it('phân trang: page từ 0, size 1–100 (mặc định 20) bị kẹp; trang rỗng có totalPages 0', async () => {
      const page1 = dataOf<Page<ArticleSummary>>(await get('/api/articles', { page: 1, size: 4 }));
      expect(page1).toMatchObject({ page: 1, size: 4, totalElements: 10, totalPages: 3 });
      expect(ids(page1.content)).toEqual([4, 5, 6, 7]);
      expect(dataOf<Page<ArticleSummary>>(await get('/api/articles', { size: 0 })).size).toBe(1);
      expect(dataOf<Page<ArticleSummary>>(await get('/api/articles', { size: 500 })).size).toBe(100);
      expect(dataOf<Page<ArticleSummary>>(await get('/api/articles', { page: -3 })).page).toBe(0);
      expect(dataOf<Page<ArticleSummary>>(await get('/api/articles', { q: 'không-có-bài-nào' }))).toMatchObject({ content: [], totalPages: 0 });
    });
  });

  describe('chi tiết và soạn bài (#8, #9)', () => {
    it('GET /articles/:id: đủ 12 trường kèm content (kể cả bài nháp và bài đã ẩn); id lạ → 404; id không phải số → 400', async () => {
      const draft = dataOf<Article>(await get('/api/articles/6'));
      expect(Object.keys(draft).sort()).toEqual(
        ['articleId', 'authorDisplayName', 'categoryId', 'content', 'coverImageUrl', 'createdAt', 'firstPublishedAt', 'slug', 'status', 'summary', 'title', 'updatedAt'].sort(),
      );
      expect(draft).toMatchObject({ status: 'DRAFT', slug: 'meo-cao-sofa-5-cach-ngan-chan', firstPublishedAt: null });
      expect(draft.content).toContain('<h2>');
      expect((await article(8)).status).toBe('HIDDEN');
      const missing = await failure(get('/api/articles/999'));
      expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy bài viết #999']);
      expect((await failure(get('/api/articles/abc'))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('POST: 201, bản nháp chưa xuất bản; content là HTML đã lọc; tiêu đề, tóm tắt, tác giả được chuẩn hóa NFC và cắt khoảng trắng', async () => {
      freezeAt(FROZEN);
      const res = await post('/api/articles', {
        categoryId: 1,
        title: '  Đường đi '.normalize('NFD'),
        slug: 'duong-di',
        coverImageUrl: 'https://cdn.petcare.vn/bia.jpg',
        summary: ' Tóm tắt '.normalize('NFD'),
        content: '<p onclick="x()">Chào</p><script>alert(1)</script>',
        authorDisplayName: ' BS. An ',
      });
      expect(res.status).toBe(201);
      expect(dataOf<Article>(res)).toEqual({
        articleId: 101,
        categoryId: 1,
        title: 'Đường đi',
        slug: 'duong-di',
        coverImageUrl: 'https://cdn.petcare.vn/bia.jpg',
        summary: 'Tóm tắt',
        content: '<p>Chào</p>',
        authorDisplayName: 'BS. An',
        status: 'DRAFT',
        firstPublishedAt: null,
        createdAt: NOW_VN,
        updatedAt: NOW_VN,
      });
      expect(await article(101)).toMatchObject({ content: '<p>Chào</p>' });
    });

    it('POST: ảnh bìa và tóm tắt không gửi (hoặc tóm tắt chỉ khoảng trắng) thì là null; bài có thể thuộc chuyên mục ẩn', async () => {
      const bare = await create({ categoryId: 4, summary: '   ' });
      expect(bare).toMatchObject({ coverImageUrl: null, summary: null, categoryId: 4 });
      expect(await create({ slug: 'bai-thu-2', coverImageUrl: null, summary: null })).toMatchObject({ coverImageUrl: null, summary: null });
    });

    it.each([
      ['thiếu categoryId', { categoryId: undefined }, 'categoryId: Vui lòng chọn chuyên mục'],
      ['categoryId = 0', { categoryId: 0 }, 'categoryId: Mã chuyên mục phải là số dương'],
      ['thiếu tiêu đề', { title: undefined }, 'title: Vui lòng nhập tiêu đề'],
      ['tiêu đề toàn khoảng trắng', { title: '   ' }, 'title: Vui lòng nhập tiêu đề'],
      ['tiêu đề quá 250 ký tự', { title: 't'.repeat(251) }, 'title: Tiêu đề tối đa 250 ký tự'],
      ['thiếu slug', { slug: undefined }, 'slug: Vui lòng nhập đường dẫn'],
      ['slug quá 270 ký tự', { slug: 's'.repeat(271) }, 'slug: Đường dẫn tối đa 270 ký tự'],
      ['slug viết hoa và có khoảng trắng', { slug: 'Sai Slug' }, SLUG_RULE],
      ['ảnh bìa http', { coverImageUrl: 'http://e.com/a.png' }, 'coverImageUrl: Ảnh bìa phải là địa chỉ https'],
      ['ảnh bìa chuỗi rỗng (tạo mới phải bỏ trường)', { coverImageUrl: '' }, 'coverImageUrl: Ảnh bìa phải là địa chỉ https'],
      ['ảnh bìa có khoảng trắng', { coverImageUrl: ' https://e.com/a.png' }, 'coverImageUrl: Ảnh bìa phải là địa chỉ https'],
      ['ảnh bìa quá 500 ký tự', { coverImageUrl: `https://e.com/${'a'.repeat(490)}` }, 'coverImageUrl: Địa chỉ ảnh bìa tối đa 500 ký tự'],
      ['tóm tắt quá 500 ký tự', { summary: 's'.repeat(501) }, 'summary: Tóm tắt tối đa 500 ký tự'],
      ['thiếu nội dung', { content: undefined }, 'content: Vui lòng nhập nội dung'],
      ['nội dung toàn khoảng trắng', { content: '  \n ' }, 'content: Vui lòng nhập nội dung'],
      ['thiếu tác giả', { authorDisplayName: undefined }, 'authorDisplayName: Vui lòng nhập tên tác giả hiển thị'],
      ['tác giả quá 100 ký tự', { authorDisplayName: 'a'.repeat(101) }, 'authorDisplayName: Tên tác giả tối đa 100 ký tự'],
    ])('POST: %s → 400 VALIDATION_FAILED, không ghi gì', async (_name, change, message) => {
      const res = await failure(post('/api/articles', { ...NEW_ARTICLE, ...change }));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: expect.stringContaining(message) });
      expect((await summaries()).totalElements).toBe(10);
    });

    it('POST: kiểu dữ liệu sai (categoryId là chữ) hoặc body không phải object → 400 MALFORMED_REQUEST', async () => {
      expect((await failure(post('/api/articles', { ...NEW_ARTICLE, categoryId: 'abc' }))).data.errorCode).toBe('MALFORMED_REQUEST');
      expect((await failure(client.post('/api/articles', null, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('POST: nội dung chỉ có thẻ bị lọc → 400 BR-BV-01 "Nội dung bài viết trống sau khi lọc"; chuyên mục lạ → 404; slug trùng → 400 BR-BV-01; không ghi gì', async () => {
      const empty = await failure(post('/api/articles', { ...NEW_ARTICLE, content: '<script>alert(1)</script><p>  </p>' }));
      expect(empty.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Nội dung bài viết trống sau khi lọc (BR-BV-01)' });
      const unknown = await failure(post('/api/articles', { ...NEW_ARTICLE, categoryId: 999 }));
      expect([unknown.status, unknown.data.message]).toEqual([404, 'Không tìm thấy chuyên mục #999']);
      const dup = await failure(post('/api/articles', { ...NEW_ARTICLE, slug: seedContent().articles[3].slug }));
      expect(ruleIdOf(dup.data.message)).toBe('BR-BV-01');
      expect((await summaries()).totalElements).toBe(10);
    });

    it('POST: slug của bài đã tạo bị chiếm thì bài sau không dùng được (kể cả khi bài trước đã ẩn)', async () => {
      await create();
      expect((await failure(post('/api/articles', NEW_ARTICLE))).data.message).toBe('Đường dẫn bài viết đã tồn tại (BR-BV-01)');
    });
  });

  describe('sửa bài (#10)', () => {
    it('chỉ trường có gửi mới đổi; trường vắng hoặc null giữ nguyên; updatedAt cập nhật và bài lên đầu danh sách', async () => {
      freezeAt(FROZEN);
      const before = await article(10);
      const res = await patch('/api/articles/10', { title: '  Tiêu đề mới ', summary: null, authorDisplayName: undefined });
      expect(res.status).toBe(200);
      const after = dataOf<Article>(res);
      expect(after).toEqual({ ...before, title: 'Tiêu đề mới', updatedAt: NOW_VN });
      expect(ids((await summaries()).content)[0]).toBe(10);
    });

    it('chuỗi rỗng xóa ảnh bìa và tóm tắt; ảnh bìa mới phải là https', async () => {
      const withCover = await create({ coverImageUrl: 'https://e.com/a.png', summary: 'Tóm tắt' });
      const cleared = dataOf<Article>(await patch(`/api/articles/${withCover.articleId}`, { coverImageUrl: '', summary: '' }));
      expect(cleared).toMatchObject({ coverImageUrl: null, summary: null });
      const set = dataOf<Article>(await patch(`/api/articles/${withCover.articleId}`, { coverImageUrl: 'https://e.com/b.png' }));
      expect(set.coverImageUrl).toBe('https://e.com/b.png');
      expect((await failure(patch(`/api/articles/${withCover.articleId}`, { coverImageUrl: 'http://e.com/b.png' }))).data.message).toBe(
        'coverImageUrl: Ảnh bìa phải là địa chỉ https',
      );
      expect((await article(withCover.articleId)).coverImageUrl).toBe('https://e.com/b.png');
    });

    it('content được lọc khi sửa; content chỉ khoảng trắng → 400; content chỉ có thẻ bị lọc → 400 BR-BV-01', async () => {
      const edited = dataOf<Article>(await patch('/api/articles/6', { content: '<p>Mới</p><script>x</script><a href="javascript:x">l</a>' }));
      expect(edited.content).toBe('<p>Mới</p><a>l</a>');
      expect((await failure(patch('/api/articles/6', { content: '   ' }))).data.message).toBe('content: Nội dung không được để trống');
      expect(ruleIdOf((await failure(patch('/api/articles/6', { content: '<style>p{}</style>' }))).data.message)).toBe('BR-BV-01');
      expect((await article(6)).content).toBe('<p>Mới</p><a>l</a>');
    });

    it.each([
      ['tiêu đề trống', { title: '  ' }, 'title: Tiêu đề không được để trống'],
      ['tiêu đề quá 250 ký tự', { title: 't'.repeat(251) }, 'title: Tiêu đề tối đa 250 ký tự'],
      ['slug sai định dạng', { slug: 'Sai Slug' }, SLUG_RULE],
      ['slug rỗng', { slug: '' }, SLUG_RULE],
      ['tóm tắt quá 500 ký tự', { summary: 's'.repeat(501) }, 'summary: Tóm tắt tối đa 500 ký tự'],
      ['tác giả trống', { authorDisplayName: ' ' }, 'authorDisplayName: Tên tác giả không được để trống'],
      ['tác giả quá 100 ký tự', { authorDisplayName: 'a'.repeat(101) }, 'authorDisplayName: Tên tác giả tối đa 100 ký tự'],
      ['categoryId = 0', { categoryId: 0 }, 'categoryId: Mã chuyên mục phải là số dương'],
    ])('%s → 400 VALIDATION_FAILED', async (_name, body, message) => {
      const res = await failure(patch('/api/articles/6', body));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: expect.stringContaining(message) });
    });

    it('bài nháp đổi được slug; trùng slug bài khác → 400 BR-BV-01; gửi lại đúng slug hiện tại luôn hợp lệ', async () => {
      expect((await patch('/api/articles/6', { slug: 'ten-moi' })).data.data.slug).toBe('ten-moi');
      expect((await failure(patch('/api/articles/6', { slug: seedContent().articles[0].slug }))).data.message).toBe('Đường dẫn bài viết đã tồn tại (BR-BV-01)');
      expect((await patch('/api/articles/6', { slug: 'ten-moi', title: 'Vẫn hợp lệ' })).status).toBe(200);
    });

    it('slug bị khóa sau lần xuất bản đầu tiên (BR-BV-01), kể cả khi bài đang ẩn; gửi lại đúng slug cũ vẫn được', async () => {
      const locked = await failure(patch('/api/articles/1', { slug: 'doi-slug' }));
      expect(locked.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Không đổi được đường dẫn sau lần xuất bản đầu tiên (BR-BV-01)' });
      expect((await failure(patch('/api/articles/8', { slug: 'doi-slug' }))).data.message).toBe('Không đổi được đường dẫn sau lần xuất bản đầu tiên (BR-BV-01)');
      const same = await patch('/api/articles/1', { slug: seedContent().articles[0].slug, title: 'Tiêu đề sửa' });
      expect(same.data.data).toMatchObject({ title: 'Tiêu đề sửa', slug: seedContent().articles[0].slug });
    });

    it('bài nháp vừa đăng xong thì bị khóa slug ngay', async () => {
      const draft = await create();
      await patch(`/api/articles/${draft.articleId}`, { slug: 'doi-khi-con-nhap' });
      await post(`/api/articles/${draft.articleId}/publish`);
      expect((await failure(patch(`/api/articles/${draft.articleId}`, { slug: 'doi-khi-da-dang' }))).data.message).toBe(
        'Không đổi được đường dẫn sau lần xuất bản đầu tiên (BR-BV-01)',
      );
    });

    it('chuyển chuyên mục; chuyên mục lạ → 404; lỗi thì không ghi dở (tiêu đề mới không được giữ lại)', async () => {
      expect(dataOf<Article>(await patch('/api/articles/6', { categoryId: 3 })).categoryId).toBe(3);
      expect((await failure(patch('/api/articles/6', { title: 'Không được ghi', categoryId: 999 }))).status).toBe(404);
      expect((await failure(patch('/api/articles/6', { title: 'Không được ghi', slug: seedContent().articles[0].slug }))).status).toBe(400);
      expect((await article(6)).title).not.toBe('Không được ghi');
    });

    it('PATCH không đổi gì (body rỗng hoặc gửi lại đúng giá trị) thì không bump updatedAt', async () => {
      freezeAt(FROZEN);
      const before = await article(6);
      await patch('/api/articles/6', {});
      await patch('/api/articles/6', { title: before.title, slug: before.slug, summary: before.summary });
      expect((await article(6)).updatedAt).toBe(before.updatedAt);
      await patch('/api/articles/6', { title: 'Khác' });
      expect((await article(6)).updatedAt).toBe(NOW_VN);
    });

    it('id lạ → 404; body không phải object → 400 MALFORMED_REQUEST', async () => {
      expect((await failure(patch('/api/articles/9999', { title: 'x' }))).data.message).toBe('Không tìm thấy bài viết #9999');
      expect((await failure(patch('/api/articles/6', null))).data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });

  describe('vòng đời: xuất bản, gỡ, xóa (#11–13; BR-BV-02)', () => {
    it('đăng bài nháp: PUBLISHED, ghi firstPublishedAt đúng một lần, cập nhật updatedAt', async () => {
      freezeAt(FROZEN);
      const res = await post('/api/articles/6/publish');
      expect(res.status).toBe(200);
      expect(dataOf<Article>(res)).toMatchObject({ articleId: 6, status: 'PUBLISHED', firstPublishedAt: NOW_VN, updatedAt: NOW_VN });
    });

    it('đăng bài đã ẩn: PUBLISHED trở lại, firstPublishedAt giữ nguyên ngày đăng đầu', async () => {
      freezeAt(FROZEN);
      const res = dataOf<Article>(await post('/api/articles/8/publish'));
      expect(res).toMatchObject({ status: 'PUBLISHED', firstPublishedAt: FIRST_PUBLISHED_OF_HIDDEN, updatedAt: NOW_VN });
    });

    it('gỡ rồi đăng lại nhiều lần không đổi firstPublishedAt', async () => {
      freezeAt('2026-10-26T03:00:00Z');
      const draft = await create();
      const first = dataOf<Article>(await post(`/api/articles/${draft.articleId}/publish`));
      vi.setSystemTime(new Date('2026-11-02T03:00:00Z'));
      await post(`/api/articles/${draft.articleId}/hide`);
      const again = dataOf<Article>(await post(`/api/articles/${draft.articleId}/publish`));
      expect(again.firstPublishedAt).toBe(first.firstPublishedAt);
      expect(again.updatedAt).toBe('2026-11-02T10:00:00+07:00');
    });

    it('đăng khi đã PUBLISHED → 409 INVALID_STATE_TRANSITION', async () => {
      const res = await failure(post('/api/articles/1/publish'));
      expect(res.data).toMatchObject({
        errorCode: 'INVALID_STATE_TRANSITION',
        message: 'Không thể chuyển trạng thái Article từ PUBLISHED sang PUBLISHED',
        statusCode: 409,
      });
    });

    it('gỡ bài đang PUBLISHED → HIDDEN, giữ firstPublishedAt', async () => {
      freezeAt(FROZEN);
      const res = dataOf<Article>(await post('/api/articles/1/hide'));
      expect(res).toMatchObject({ status: 'HIDDEN', firstPublishedAt: seedContent().articles[0].firstPublishedAt, updatedAt: NOW_VN });
    });

    it.each([
      [6, 'DRAFT'],
      [8, 'HIDDEN'],
    ])('gỡ bài không PUBLISHED (#%i, %s) → 409', async (id, status) => {
      const res = await failure(post(`/api/articles/${id}/hide`));
      expect(res.data).toMatchObject({
        errorCode: 'INVALID_STATE_TRANSITION',
        message: `Không thể chuyển trạng thái Article từ ${status} sang HIDDEN`,
      });
      expect((await article(id)).status).toBe(status);
    });

    it('id lạ → 404, id không phải số → 400 MALFORMED_REQUEST', async () => {
      expect((await failure(post('/api/articles/999/publish'))).data.message).toBe('Không tìm thấy bài viết #999');
      expect((await failure(post('/api/articles/abc/hide'))).data.errorCode).toBe('MALFORMED_REQUEST');
    });

    it('xóa bài chưa từng xuất bản → 204 và biến mất; xóa lại → 404', async () => {
      const res = await del('/api/articles/6');
      expect([res.status, res.data]).toEqual([204, '']);
      expect((await failure(get('/api/articles/6'))).status).toBe(404);
      expect((await summaries()).totalElements).toBe(9);
      expect((await failure(del('/api/articles/6'))).status).toBe(404);
    });

    it('xóa bài đã từng xuất bản → 400 BR-BV-02 "chỉ ẩn được", kể cả khi đang ẩn; bài vẫn còn', async () => {
      const published = await failure(del('/api/articles/1'));
      expect(published.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Bài viết đã từng xuất bản, chỉ ẩn được (BR-BV-02)' });
      expect(ruleIdOf((await failure(del('/api/articles/8'))).data.message)).toBe('BR-BV-02');
      const draft = await create();
      await post(`/api/articles/${draft.articleId}/publish`);
      await post(`/api/articles/${draft.articleId}/hide`);
      expect(ruleIdOf((await failure(del(`/api/articles/${draft.articleId}`))).data.message)).toBe('BR-BV-02');
      expect((await summaries()).totalElements).toBe(11);
    });
  });

  describe('trang công khai đổi theo ghi từ quản trị (đọc lại state mỗi request)', () => {
    it('đăng bài nháp → hiện đầu /public/articles và đọc được theo slug; gỡ → biến mất, slug 404', async () => {
      freezeAt(FROZEN);
      expect(slugs((await articles()).content)).not.toContain(DRAFT);
      await post('/api/articles/6/publish');
      const list = (await articles()).content;
      expect(list).toHaveLength(9);
      expect(list[0]).toMatchObject({ slug: DRAFT, publishedAt: NOW_VN, categorySlug: 'kien-thuc-nuoi-thu-cung' });
      expect((await client.get(`/api/public/articles/${DRAFT}`)).status).toBe(200);

      await post('/api/articles/6/hide');
      expect(slugs((await articles()).content)).not.toContain(DRAFT);
      expect((await failure(client.get(`/api/public/articles/${DRAFT}`))).status).toBe(404);
    });

    it('bài đang hiện bị gỡ thì biến mất; đăng lại thì hiện đúng vị trí theo ngày đăng đầu, không phải ngày đăng lại', async () => {
      freezeAt(FROZEN);
      await post(`/api/articles/${(await summaries()).content.find((a) => a.slug === NEWEST)!.articleId}/hide`);
      expect(slugs((await articles()).content)).not.toContain(NEWEST);
      await post('/api/articles/8/publish');
      const list = (await articles()).content;
      expect(list.find((a) => a.slug === HIDDEN)).toMatchObject({ publishedAt: FIRST_PUBLISHED_OF_HIDDEN });
      expect(list[0].slug).not.toBe(HIDDEN);
    });

    it('bài nháp / bài mới tạo không hiện ở trang công khai cho tới khi đăng', async () => {
      const draft = await create();
      expect(slugs((await articles()).content)).not.toContain(draft.slug);
      expect((await failure(client.get(`/api/public/articles/${draft.slug}`))).status).toBe(404);
    });

    it('sửa bài đang hiện → thẻ công khai đổi tiêu đề, nội dung (đã lọc) và chuyên mục', async () => {
      await patch('/api/articles/1', { title: 'Tiêu đề công khai mới', categoryId: 3, content: '<p>Nội dung mới</p><script>x</script>' });
      const detail = (await client.get(`/api/public/articles/${NEWEST}`)).data.data as PublicArticle;
      expect(detail).toMatchObject({ title: 'Tiêu đề công khai mới', categorySlug: 'cham-soc-hang-ngay', content: '<p>Nội dung mới</p>' });
    });

    it('ẩn chuyên mục → biến khỏi danh sách công khai nhưng bài của nó vẫn đọc và lọc được (BR-BV-04); hiện lại thì quay về', async () => {
      await patch('/api/article-categories/2', { isHidden: true });
      const visible = (await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[];
      expect(visible.map((c) => c.slug)).not.toContain('suc-khoe-thu-y');
      expect((await articles({ categorySlug: 'suc-khoe-thu-y' })).totalElements).toBe(3);
      await patch('/api/article-categories/2', { isHidden: false });
      expect(((await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[]).map((c) => c.slug)).toContain('suc-khoe-thu-y');
    });

    it('chuyên mục mới + bài đăng trong đó → lọc được theo categorySlug; đổi tên chuyên mục → categoryName của thẻ đổi theo', async () => {
      const category = dataOf<ArticleCategory>(await post('/api/article-categories', { name: 'Dinh dưỡng', slug: 'dinh-duong' }));
      const draft = await create({ categoryId: category.categoryId, slug: 'bai-dinh-duong' });
      await post(`/api/articles/${draft.articleId}/publish`);
      expect(slugs((await articles({ categorySlug: 'dinh-duong' })).content)).toEqual(['bai-dinh-duong']);
      await patch(`/api/article-categories/${category.categoryId}`, { name: 'Ăn uống' });
      expect((await articles({ categorySlug: 'dinh-duong' })).content[0].categoryName).toBe('Ăn uống');
    });

    it('xóa bài nháp không ảnh hưởng trang công khai; thao tác bị từ chối không làm đổi gì', async () => {
      const before = slugs((await articles()).content);
      await del('/api/articles/6');
      await failure(del('/api/articles/1'));
      await failure(post('/api/articles/1/publish'));
      expect(slugs((await articles()).content)).toEqual(before);
    });

    it('thay đổi được ghi vào localStorage ở CONTENT_STATE_KEY (trang công khai đọc đúng chỗ này)', async () => {
      const draft = await create();
      const stored = JSON.parse(localStorage.getItem(CONTENT_STATE_KEY)!) as ReturnType<typeof seedContent>;
      expect(stored.articles.find((a) => a.articleId === draft.articleId)).toMatchObject({ slug: 'bai-thu', status: 'DRAFT' });
      expect(stored.articles).toHaveLength(11);
    });
  });

  describe('state localStorage cũ vẫn dùng được (load gộp với dữ liệu mẫu)', () => {
    it('state hình dạng cũ (T46, đủ seq / categories / articles): đọc, tạo bài mới, trang công khai thấy', async () => {
      const old = seedContent();
      old.articles.find((a) => a.articleId === 6)!.title = 'Bài nháp sửa tay';
      localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(old));
      expect((await article(6)).title).toBe('Bài nháp sửa tay');
      const created = await create();
      expect(created.articleId).toBe(101);
      expect((await summaries()).totalElements).toBe(11);
      await post(`/api/articles/${created.articleId}/publish`);
      expect(slugs((await articles()).content)).toContain('bai-thu');
    });

    it('state cũ thiếu seq: id mới vẫn là số hợp lệ và không đè bài cũ', async () => {
      const withoutSeq: Partial<ReturnType<typeof seedContent>> = seedContent();
      delete withoutSeq.seq;
      localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(withoutSeq));
      const created = await create();
      expect(Number.isInteger(created.articleId)).toBe(true);
      expect(created.articleId).toBeGreaterThan(10);
      const category = dataOf<ArticleCategory>(await post('/api/article-categories', { name: 'Mới', slug: 'moi' }));
      expect(Number.isInteger(category.categoryId)).toBe(true);
      expect((await summaries()).totalElements).toBe(11);
    });

    it('JSON hỏng trong localStorage → quay về dữ liệu mẫu, không văng lỗi', async () => {
      localStorage.setItem(CONTENT_STATE_KEY, '{không phải json');
      expect((await summaries()).totalElements).toBe(10);
    });
  });
});
