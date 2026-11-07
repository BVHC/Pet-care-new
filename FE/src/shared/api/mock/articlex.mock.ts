// Mock articlex-v1 (UC80, UC81 mở rộng, UC82; tầng 3, docs/api/articlex-v1.md #1–14, 08 §F.3 BR-BV-05…08, FSM #21): bác sĩ (VET) soạn bài → gửi duyệt → SUPER_MANAGER duyệt / từ chối;
// khách bình luận bài đã xuất bản, SUPER_MANAGER ẩn / bỏ ẩn.
//
// Bài của bác sĩ nằm trong state của mock bài viết (content.mock.ts, `loadContent` / `saveContent`): cùng bảng `articles`, cùng dãy id, cùng kiểm slug trùng, cùng bộ lọc PUBLISHED
// của trang công khai — nên bài vừa duyệt hiện ngay cùng bài của SUPER_MANAGER ở /api/public/articles, và hàng chờ duyệt là `GET /api/articles?status=PENDING_REVIEW` của content-v1
// (content.mock.ts đã mở rộng `status`, ẩn nháp của bác sĩ khỏi SUPER_MANAGER và chặn sửa / xóa bài của bác sĩ). Bình luận có state riêng `petcare-mock-articlex` (bảng article_comments).
// Nạp module này KHÔNG ghi gì; bài mẫu của bác sĩ chỉ được tạo (một lần, ensureDemoArticles) khi một route `/me/articles…`, approve / reject, hoặc truy vấn hàng chờ duyệt đầu tiên chạy.
//
// ASSUMPTION (hợp đồng im lặng hoặc mock đơn giản hơn BE; BE tầng 3 CHƯA cài nên không có số đo thật):
//  A1  Thứ tự kiểm theo 00-method §3.5 và brief T57: 401 → 400 sai kiểu (id không phải số, body sai kiểu) → 403 vai trò → 400 VALIDATION_FAILED (độ dài, định dạng ảnh bìa, thiếu nội dung)
//      → 404 (kể cả bài của người khác: 404 theo D.1) → 403 phạm vi (chỉ bình luận của người khác) → 400 BR-BV-0x → 409. Yaml ghi 400 `BR-BV-01` cho "tiêu đề rỗng / quá dài" và
//      "chuyên mục không tồn tại" (chứ không phải VALIDATION_FAILED, và chuyên mục lạ là 400 chứ không phải 404 như content-v1), `BR-BV-08` cho bình luận rỗng / quá dài, `BR-BV-06` /
//      `BR-BV-08` cho thiếu lý do: mock làm đúng như vậy dù `maxLength` cũng có trong schema.
//  A2  Slug do mock sinh từ tiêu đề (bỏ dấu, nối gạch, thêm -2, -3… khi trùng) ngay khi tạo bài; đổi tiêu đề khi chưa xuất bản thì sinh lại; khóa sau lần xuất bản đầu (BR-BV-01).
//      `authorDisplayName` lấy tên nhân viên của VET (BR-TK-20), làm mới lúc duyệt. Chuyên mục do VET chọn (Q1 đề xuất), bắt buộc lúc gửi duyệt (BR-BV-01 thiếu chuyên mục).
//  A3  Sửa (PATCH) bài REJECTED luôn đưa về DRAFT và xóa `reviewNote`, kể cả body rỗng (hợp đồng chỉ ghi "khi sửa"). Ảnh bìa chỉ nhận `https://…` (pattern của yaml), nên không xóa
//      được ảnh bìa bằng chuỗi rỗng. Nội dung đi qua cùng bộ lọc HTML như content-v1 (ADR-0071).
//  A4  Bình luận của người khác: sửa / xóa trả 403 ACCESS_DENIED_SCOPE_MISMATCH, không phải 404 (id bình luận lộ công khai ở #9 nên không cần giấu). Xóa mềm lặp lại vẫn 204. Khách xóa được cả
//      bình luận đang bị ẩn (thành DELETED, mất đường bỏ ẩn). Sửa bình luận không tính vào giới hạn 10 / giờ; mọi bình luận tạo trong 60 phút qua của khách tính, kể cả đã xóa / bị ẩn
//      ([CFG] `content.comment_per_hour`, mock không đọc cấu hình nên là hằng số).
//  A5  Tên tác giả bình luận rút gọn = họ (từ đầu) + chữ cái đầu của tên (từ cuối) + ".", vd "Nguyễn Thu Trang" → "Nguyễn T.".
//  A6  Hợp đồng KHÔNG có endpoint liệt kê bình luận đã ẩn: SUPER_MANAGER bỏ ẩn (#14) chỉ làm được khi đã biết `commentId` (vừa ẩn xong, hoặc id bình luận ẩn mẫu). #9 chỉ trả ACTIVE.
//  A7  Thông báo trong ứng dụng (ARTICLE_REVIEW_SUBMITTED / _APPROVED / _REJECTED, 06 §8 Q4) đẩy qua `pushNotification` của care.mock; không có email, không ghi audit (BR-QT-15).
import type { AccountSummary } from '../../types/auth';
import { ARTICLEX_LIMITS, type ArticleReviewStatus, type Comment, type CommentStatus, type MyArticle, type MyArticleSummary, type PublicComment } from '../../types/articlex';
import { foldVi } from '../../utils/text';
import { pushNotification } from './care.mock';
import { cleanContent, loadContent, registerReviewQueueHook, saveContent, type StoredArticle } from './content.mock';
import { findCustomer, customerOfAccount } from './customer.mock';
import { denied, fail, has, invalid, isText, noContent, notFound, ok, ruleFail, scopeDenied, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { staffNameOf } from './identity-admin.mock';
import { MOCK_ACCOUNTS } from './identity.mock';
import { clampPaging, pageOf, typeError } from './public.mock';

// ─── State: bảng `article_comments` (ROOT) ───

interface CommentRow {
  commentId: number;
  articleId: number;
  customerId: number;
  content: string;
  status: CommentStatus;
  createdAt: string;
  editedAt: string | null;
  hiddenBy: number | null;
  hideReason: string | null;
  hiddenAt: string | null;
}
interface State {
  seq: number;
  comments: CommentRow[];
}

const STATE_KEY = 'petcare-mock-articlex';
const HOUR = 3_600_000;
const DAY = 24 * HOUR;
/** Giờ hiện tại dạng ISO có offset +07:00 như dữ liệu mẫu của mock bài viết (không phụ thuộc múi giờ máy). */
const isoAt = (ms: number) => new Date(ms + 7 * HOUR).toISOString().slice(0, 19) + '+07:00';
const nowIso = () => isoAt(Date.now());

/**
 * Bình luận mẫu (thời gian tính lùi từ lúc tạo): bài 1 (Royal Canin vs Pedigree) có 2 bình luận hiện, 1 bị ẩn, 1 đã xóa; bài 2 có 1; bài 14 (bài mẫu của bác sĩ, chỉ có sau
 * ensureDemoArticles) có 1. Khách 3 và 4 là hồ sơ tại quầy chưa có tài khoản (chỉ để danh sách bình luận có nhiều tên).
 */
function seedComments(now = Date.now()): State {
  const row = (r: Pick<CommentRow, 'commentId' | 'articleId' | 'customerId' | 'content'> & Partial<CommentRow>, daysAgo: number): CommentRow => ({
    status: 'ACTIVE',
    createdAt: isoAt(now - daysAgo * DAY),
    editedAt: null,
    hiddenBy: null,
    hideReason: null,
    hiddenAt: null,
    ...r,
  });
  return {
    seq: 6,
    comments: [
      row({ commentId: 1, articleId: 1, customerId: 1, content: 'Cảm ơn bác sĩ, bé nhà mình đang ăn Royal Canin Mini và rất hợp, lông mượt hơn sau hai tháng.' }, 5),
      row({ commentId: 2, articleId: 1, customerId: 4, content: 'Bé nhà mình kén ăn nên chuyển sang Pedigree vị gà, vẫn khỏe. Mong bài sau có so sánh thức ăn hạt cho chó con.', editedAt: isoAt(now - 4 * DAY) }, 4.5),
      row({ commentId: 3, articleId: 1, customerId: 3, content: 'Ai cần mua sỉ thức ăn hạt giá rẻ liên hệ mình nhé, giao tận nơi.', status: 'HIDDEN', hiddenBy: 2, hideReason: 'Nội dung quảng cáo, không liên quan bài viết', hiddenAt: isoAt(now - 3 * DAY) }, 4),
      row({ commentId: 4, articleId: 2, customerId: 1, content: 'Mèo nhà mình từng bỏ ăn hai ngày và phải truyền dịch, bài viết rất đúng. Cảm ơn phòng khám.' }, 2),
      row({ commentId: 5, articleId: 1, customerId: 1, content: 'Bình luận nhầm bài, mình xóa.', status: 'DELETED' }, 6),
      row({ commentId: 6, articleId: 14, customerId: 1, content: 'Lịch tiêm rất dễ theo dõi, mình đã đặt lịch mũi nhắc cho Bim.' }, 1),
    ],
  };
}

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    // Gộp với dữ liệu mẫu: state thiếu bảng (vd chỉ `{}`) vẫn đọc được; bộ đếm id không bao giờ thấp hơn id lớn nhất đang có.
    const s: State = { ...seedComments(), ...(raw ? (JSON.parse(raw) as Partial<State>) : {}) };
    return { ...s, seq: Math.max(s.seq, ...s.comments.map((c) => c.commentId)) };
  } catch {
    return seedComments();
  }
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Bài mẫu của bác sĩ (tạo lười, một lần) ───

const DEMO_IDS = [11, 12, 13, 14, 15, 16];
const VET_MINH_ANH = 5; // doctor@store1.vn
const VET_QUOC_BAO = 21; // doctor2@store1.vn
const SUPER_MANAGER_IDS = MOCK_ACCOUNTS.filter((a) => a.role === 'SUPER_MANAGER').map((a) => a.id);
const html = (...blocks: string[]) => blocks.join('');

/**
 * Sáu bài mẫu (id 11–16, chuyên mục 2 "Sức khỏe thú y" và 3 "Chăm sóc hằng ngày"): BS. Minh Anh có 1 nháp, 1 chờ duyệt, 1 bị từ chối, 1 đã xuất bản; BS. Quốc Bảo có 1 chờ duyệt, 1 đã xuất bản
 * rồi bị ẩn. Idempotent; không chạy lúc nạp module mà khi một route của module này (hoặc hàng chờ duyệt của content-v1) được dùng, để các test cũ của mock bài viết không thấy bài mới.
 */
export function ensureDemoArticles(now = Date.now()) {
  const state = loadContent();
  const rows = state.articles as unknown as StoredArticle[];
  if (rows.some((a) => DEMO_IDS.includes(a.articleId))) return;
  const draftOf = (r: Pick<StoredArticle, 'articleId' | 'authorAccountId' | 'categoryId' | 'title' | 'slug' | 'summary' | 'content' | 'status'> & Partial<StoredArticle>, daysAgo: number): StoredArticle => ({
    coverImageUrl: null,
    authorDisplayName: staffNameOf(r.authorAccountId!) ?? 'Bác sĩ',
    firstPublishedAt: null,
    reviewNote: null,
    submittedAt: null,
    reviewedBy: null,
    reviewedAt: null,
    createdAt: isoAt(now - (daysAgo + 1) * DAY),
    updatedAt: isoAt(now - daysAgo * DAY),
    ...r,
  });
  const published = (daysAgo: number) => ({ firstPublishedAt: isoAt(now - daysAgo * DAY), submittedAt: isoAt(now - (daysAgo + 1) * DAY), reviewedBy: 2, reviewedAt: isoAt(now - daysAgo * DAY) });
  rows.push(
    draftOf({
      articleId: 11, authorAccountId: VET_MINH_ANH, categoryId: 2, status: 'DRAFT', title: 'Chăm sóc răng miệng cho chó: 5 thói quen mỗi tuần', slug: 'cham-soc-rang-mieng-cho-cho-5-thoi-quen-moi-tuan',
      summary: 'Cao răng là nguyên nhân hàng đầu gây hôi miệng và viêm lợi ở chó trưởng thành.',
      content: html('<p>Bản nháp: cần bổ sung hình minh họa cách đánh răng và danh sách sản phẩm vệ sinh răng miệng.</p>', '<h2>Năm thói quen</h2>', '<ul><li>Chải răng 2 đến 3 lần mỗi tuần</li><li>Cho nhai đồ gặm an toàn</li><li>Khám răng định kỳ mỗi 6 tháng</li></ul>'),
    }, 1),
    draftOf({
      articleId: 12, authorAccountId: VET_MINH_ANH, categoryId: 2, status: 'PENDING_REVIEW', title: 'Dấu hiệu mèo bị viêm đường tiết niệu cần đi khám sớm', slug: 'dau-hieu-meo-viem-duong-tiet-nieu-can-di-kham-som',
      summary: 'Đi vệ sinh nhiều lần, rặn nhưng ít nước tiểu hoặc tiểu ra máu là những dấu hiệu không nên chờ.',
      content: html('<p>Viêm đường tiết niệu ở mèo, đặc biệt là mèo đực, có thể gây tắc nghẽn trong vòng vài giờ.</p>', '<h2>Khi nào cần đi khám ngay</h2>', '<ul><li>Rặn nhiều lần mà không ra nước tiểu</li><li>Tiểu ra máu hoặc kêu khi đi tiểu</li><li>Liếm vùng bụng dưới liên tục</li></ul>', '<p>Hãy gọi phòng khám trước khi đến để được chuẩn bị sẵn.</p>'),
      submittedAt: isoAt(now - 1 * DAY),
    }, 1),
    draftOf({
      articleId: 13, authorAccountId: VET_MINH_ANH, categoryId: 3, status: 'REJECTED', title: 'Cho chó ăn xương có an toàn không?', slug: 'cho-cho-an-xuong-co-an-toan-khong',
      summary: 'Xương có thể là món ăn vặt hấp dẫn nhưng cũng là nguyên nhân phổ biến của các ca cấp cứu tiêu hóa.',
      content: html('<p>Nhiều chủ nuôi cho chó gặm xương như một cách giải trí, nhưng không phải loại xương nào cũng an toàn.</p>', '<p>Xương nấu chín dễ vỡ thành mảnh sắc, có thể gây thủng ruột.</p>'),
      submittedAt: isoAt(now - 4 * DAY), reviewedBy: 2, reviewedAt: isoAt(now - 3 * DAY), reviewNote: 'Cần dẫn nguồn tham khảo và bổ sung cảnh báo riêng về xương nấu chín trước khi đăng.',
    }, 3),
    draftOf({
      articleId: 14, authorAccountId: VET_MINH_ANH, categoryId: 2, status: 'PUBLISHED', title: 'Lịch tiêm phòng cơ bản cho chó con trong năm đầu', slug: 'lich-tiem-phong-co-ban-cho-cho-con-trong-nam-dau',
      summary: 'Các mũi tiêm cần có và thời điểm tiêm để bé khỏe mạnh trong năm đầu đời.',
      content: html('<p>Chó con nhận kháng thể từ sữa mẹ, nhưng lượng kháng thể này giảm dần sau 6 đến 8 tuần tuổi.</p>', '<h2>Các mũi cơ bản</h2>', '<ul><li>6 đến 8 tuần: mũi 5 bệnh lần 1</li><li>10 đến 12 tuần: mũi 5 bệnh lần 2</li><li>14 đến 16 tuần: mũi 5 bệnh lần 3 và mũi dại</li></ul>', '<p>Sau mỗi mũi, theo dõi bé 24 giờ và gọi phòng khám nếu có biểu hiện lạ.</p>'),
      ...published(10),
    }, 10),
    draftOf({
      articleId: 15, authorAccountId: VET_QUOC_BAO, categoryId: 3, status: 'PENDING_REVIEW', title: 'Chế độ ăn cho mèo sau triệt sản', slug: 'che-do-an-cho-meo-sau-triet-san',
      summary: 'Mèo sau triệt sản dễ tăng cân, cần điều chỉnh khẩu phần trong những tuần đầu.',
      content: html('<p>Sau khi triệt sản, nhu cầu năng lượng của mèo giảm khoảng 20 đến 30 phần trăm.</p>', '<ul><li>Chia nhỏ khẩu phần thành 3 đến 4 bữa</li><li>Chọn thức ăn hạt dành cho mèo đã triệt sản</li></ul>'),
      submittedAt: isoAt(now - 6 * HOUR),
    }, 0.25),
    draftOf({
      articleId: 16, authorAccountId: VET_QUOC_BAO, categoryId: 3, status: 'HIDDEN', title: 'Mẹo xử lý khi chó bị say xe', slug: 'meo-xu-ly-khi-cho-bi-say-xe',
      summary: 'Một vài mẹo giúp chó thoải mái hơn trong những chuyến đi dài.',
      content: html('<p>Nội dung đã được ẩn để cập nhật liều dùng thuốc chống say xe. Tác giả soạn bài mới thay thế.</p>'),
      ...published(30),
    }, 8),
  );
  saveContent(state);
}
registerReviewQueueHook(ensureDemoArticles);

// ─── Đọc dữ liệu vào ───

type Body = Record<string, unknown>;
const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const COVER_URL = /^https:\/\/\S+$/;
const ARTICLE_STATUSES: readonly string[] = ['DRAFT', 'PENDING_REVIEW', 'REJECTED', 'PUBLISHED', 'HIDDEN'];
const norm = (s: string) => s.normalize('NFC').trim();
const blankToNull = (s: string) => (s.trim() === '' ? null : s);
/** Số ký tự theo code point (emoji một ký tự), như `@Size` trên chuỗi đã chuẩn hóa. */
const length = (s: string) => [...s].length;

/** Body phải là object và các trường đã gửi đúng kiểu; không thì `null` (BE: HttpMessageNotReadableException). */
function readBody(body: unknown, kinds: { text?: string[]; int?: string[] }): Body | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null;
  const b = body as Body;
  const wrong = (names: string[] | undefined, right: (v: unknown) => boolean) => (names ?? []).some((n) => has(b[n]) && !right(b[n]));
  return wrong(kinds.text, (v) => typeof v === 'string') || wrong(kinds.int, isInt) ? null : b;
}

const role = (req: MockRequest) => req.account!.role;
const me = (req: MockRequest): AccountSummary => req.account!;

// ─── Bài viết của tôi (#1–6) ───

const rowsOf = () => {
  const state = loadContent();
  return { state, rows: state.articles as unknown as StoredArticle[] };
};
const mine = (rows: StoredArticle[], accountId: number) => rows.filter((a) => a.authorAccountId === accountId);

const view = (a: StoredArticle): MyArticle => ({
  articleId: a.articleId,
  title: a.title,
  slug: a.slug,
  categoryId: a.categoryId,
  summary: a.summary,
  content: a.content,
  coverImageUrl: a.coverImageUrl,
  status: a.status,
  reviewNote: a.reviewNote ?? null,
  submittedAt: a.submittedAt ?? null,
  publishedAt: a.firstPublishedAt,
  updatedAt: a.updatedAt,
});
const summaryOf = (a: StoredArticle): MyArticleSummary => ({
  articleId: a.articleId,
  title: a.title,
  status: a.status,
  submittedAt: a.submittedAt ?? null,
  publishedAt: a.firstPublishedAt,
  updatedAt: a.updatedAt,
});

/** Slug từ tiêu đề (A2): bỏ dấu, chỉ chữ thường / số nối bằng gạch ngang, tối đa 120 ký tự; trùng thì thêm -2, -3… */
function slugFor(title: string, rows: StoredArticle[], exceptId?: number): string {
  const base = foldVi(title).replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 120).replace(/-+$/, '') || 'bai-viet';
  const taken = new Set(rows.filter((a) => a.articleId !== exceptId).map((a) => a.slug));
  let slug = base;
  for (let n = 2; taken.has(slug); n++) slug = `${base}-${n}`;
  return slug;
}

const articleChecks = (b: Body, creating: boolean): Check[] => [
  ['content', 'Vui lòng nhập nội dung', creating ? !isText(b.content) : typeof b.content === 'string' && b.content.trim() === ''],
  ['summary', `Tóm tắt tối đa ${ARTICLEX_LIMITS.summary} ký tự`, tooLong(b.summary, ARTICLEX_LIMITS.summary)],
  ['coverImageUrl', `Địa chỉ ảnh bìa tối đa ${ARTICLEX_LIMITS.coverImageUrl} ký tự`, tooLong(b.coverImageUrl, ARTICLEX_LIMITS.coverImageUrl)],
  ['coverImageUrl', 'Ảnh bìa phải là địa chỉ https', typeof b.coverImageUrl === 'string' && !COVER_URL.test(b.coverImageUrl)],
];

const articleKinds = { text: ['title', 'summary', 'content', 'coverImageUrl'], int: ['categoryId'] };
const categoryExists = (id: number) => loadContent().categories.some((c) => c.categoryId === id);
const titleError = (title: unknown): MockReply | null =>
  !isText(title) ? ruleFail('BR-BV-01', 'Tiêu đề bài viết không được để trống') : tooLong(title, ARTICLEX_LIMITS.title) ? ruleFail('BR-BV-01', `Tiêu đề bài viết tối đa ${ARTICLEX_LIMITS.title} ký tự`) : null;
const emptyContent = () => ruleFail('BR-BV-01', 'Nội dung bài viết trống sau khi lọc');

const createMy: MockHandler = (req) => {
  const b = readBody(req.body, articleKinds);
  if (!b) return malformed();
  if (role(req) !== 'VET') return denied();
  const bad = invalid(articleChecks(b, true));
  if (bad) return bad;
  const refused = titleError(b.title);
  if (refused) return refused;
  const content = cleanContent(b.content as string);
  if (content === null) return emptyContent();
  if (has(b.categoryId) && !categoryExists(b.categoryId as number)) return ruleFail('BR-BV-01', 'Chuyên mục không tồn tại');

  ensureDemoArticles();
  const { state, rows } = rowsOf();
  const now = nowIso();
  const title = norm(b.title as string);
  const article: StoredArticle = {
    articleId: ++state.seq,
    categoryId: has(b.categoryId) ? (b.categoryId as number) : (null as unknown as number),
    title,
    slug: slugFor(title, rows),
    coverImageUrl: has(b.coverImageUrl) ? (b.coverImageUrl as string) : null,
    summary: has(b.summary) ? blankToNull(norm(b.summary as string)) : null,
    content,
    authorDisplayName: staffNameOf(me(req).id) ?? 'Bác sĩ',
    status: 'DRAFT',
    firstPublishedAt: null,
    createdAt: now,
    updatedAt: now,
    authorAccountId: me(req).id,
    reviewNote: null,
    submittedAt: null,
    reviewedBy: null,
    reviewedAt: null,
  };
  rows.push(article);
  saveContent(state);
  return ok(view(article), 201, 'Đã lưu bản nháp');
};

const listMy: MockHandler = (req) => {
  const status = req.query.status;
  if (status !== undefined && !ARTICLE_STATUSES.includes(status)) return badParam('status');
  const badPaging = typeError(req.query, ['page', 'size']);
  if (badPaging) return badPaging;
  if (role(req) !== 'VET') return denied();
  ensureDemoArticles();
  const found = mine(rowsOf().rows, me(req).id)
    .filter((a) => !status || a.status === status)
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || b.articleId - a.articleId);
  return ok(pageOf(found.map(summaryOf), clampPaging(req.query)));
};

interface Own {
  state: ReturnType<typeof rowsOf>['state'];
  rows: StoredArticle[];
  article: StoredArticle;
}
const isFound = (x: Own | MockReply): x is Own => 'article' in x;

/** Bài của chính VET theo `:articleId`; bài lạ hoặc của người khác cùng một 404 (D.1). */
function ownArticle(req: MockRequest): Own | MockReply {
  ensureDemoArticles();
  const { state, rows } = rowsOf();
  const article = mine(rows, me(req).id).find((a) => a.articleId === Number(req.params.articleId));
  return article ? { state, rows, article } : notFound('bài viết', req.params.articleId);
}
const stateError = (from: ArticleReviewStatus, to: ArticleReviewStatus) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái Article từ ${from} sang ${to}`, 409);

const getMy: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  if (role(req) !== 'VET') return denied();
  const found = ownArticle(req);
  return isFound(found) ? ok(view(found.article)) : found;
};

const updateMy: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  const b = readBody(req.body, articleKinds);
  if (!b) return malformed();
  if (role(req) !== 'VET') return denied();
  const bad = invalid(articleChecks(b, false));
  if (bad) return bad;
  const found = ownArticle(req);
  if (!isFound(found)) return found;
  const { state, rows, article } = found;

  if (has(b.title)) {
    const refused = titleError(b.title);
    if (refused) return refused;
  }
  const content = has(b.content) ? cleanContent(b.content as string) : undefined;
  if (content === null) return emptyContent();
  if (has(b.categoryId) && !categoryExists(b.categoryId as number)) return ruleFail('BR-BV-01', 'Chuyên mục không tồn tại');
  if (article.status !== 'DRAFT' && article.status !== 'REJECTED') {
    return fail(
      'INVALID_STATE_TRANSITION',
      article.status === 'PENDING_REVIEW'
        ? 'Bài viết đang chờ duyệt, hãy rút về nháp trước khi sửa (BR-BV-07)'
        : 'Bài viết đã xuất bản không sửa được; hãy nhờ quản lý ẩn bài và soạn bài mới (BR-BV-07)',
      409,
    );
  }

  const before = JSON.stringify(article);
  if (has(b.title)) {
    article.title = norm(b.title as string);
    if (!article.firstPublishedAt) article.slug = slugFor(article.title, rows, article.articleId);
  }
  if (has(b.categoryId)) article.categoryId = b.categoryId as number;
  if (has(b.summary)) article.summary = blankToNull(norm(b.summary as string));
  if (content !== undefined) article.content = content;
  if (has(b.coverImageUrl)) article.coverImageUrl = b.coverImageUrl as string;
  if (article.status === 'REJECTED') Object.assign(article, { status: 'DRAFT', reviewNote: null });
  if (JSON.stringify(article) !== before) article.updatedAt = nowIso();
  saveContent(state);
  return ok(view(article), 200, 'Đã cập nhật bài viết');
};

const submit: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  if (role(req) !== 'VET') return denied();
  const found = ownArticle(req);
  if (!isFound(found)) return found;
  const { state, article } = found;
  // BR-BV-01: thiếu tiêu đề, chuyên mục, nội dung thì không gửi duyệt.
  const missing = [article.categoryId == null && 'chuyên mục', cleanContent(article.content) === null && 'nội dung'].filter(Boolean);
  if (missing.length) return ruleFail('BR-BV-01', `Bài viết còn thiếu ${missing.join(', ')}, chưa gửi duyệt được`);
  if (article.status !== 'DRAFT') return stateError(article.status, 'PENDING_REVIEW');
  const now = nowIso();
  Object.assign(article, { status: 'PENDING_REVIEW', submittedAt: now, updatedAt: now });
  saveContent(state);
  for (const id of SUPER_MANAGER_IDS) {
    pushNotification(id, {
      type: 'ARTICLE_REVIEW_SUBMITTED',
      title: 'Có bài viết chờ duyệt',
      body: `${article.authorDisplayName} vừa gửi duyệt bài "${article.title}". Vào mục bài viết, lọc trạng thái Chờ duyệt để xem.`,
      linkUrl: null,
    });
  }
  return ok(view(article), 200, 'Đã gửi duyệt');
};

const withdraw: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  if (role(req) !== 'VET') return denied();
  const found = ownArticle(req);
  if (!isFound(found)) return found;
  const { state, article } = found;
  if (article.status !== 'PENDING_REVIEW') return stateError(article.status, 'DRAFT');
  Object.assign(article, { status: 'DRAFT', submittedAt: null, updatedAt: nowIso() });
  saveContent(state);
  return ok(view(article), 200, 'Đã rút bài về nháp');
};

// ─── Duyệt bài (#7–8, chỉ SUPER_MANAGER) ───

/** Bài theo `:articleId` cho người duyệt: nháp của bác sĩ không ai ngoài tác giả thấy (404, BR-BV-05). */
function reviewable(req: MockRequest) {
  ensureDemoArticles();
  const { state, rows } = rowsOf();
  const article = rows.find((a) => a.articleId === Number(req.params.articleId));
  return article && !(article.authorAccountId != null && article.status === 'DRAFT') ? { state, article } : null;
}

function notifyAuthor(article: StoredArticle, type: 'ARTICLE_REVIEW_APPROVED' | 'ARTICLE_REVIEW_REJECTED', body: string) {
  if (article.authorAccountId == null) return;
  pushNotification(article.authorAccountId, {
    type,
    title: type === 'ARTICLE_REVIEW_APPROVED' ? 'Bài viết đã được duyệt' : 'Bài viết bị từ chối',
    body,
    linkUrl: null,
  });
}

const approve: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  if (role(req) !== 'SUPER_MANAGER') return denied();
  const found = reviewable(req);
  if (!found) return notFound('bài viết', req.params.articleId);
  const { state, article } = found;
  if (article.status !== 'PENDING_REVIEW') return stateError(article.status, 'PUBLISHED');
  const now = nowIso();
  Object.assign(article, {
    status: 'PUBLISHED',
    firstPublishedAt: article.firstPublishedAt ?? now,
    reviewedBy: me(req).id,
    reviewedAt: now,
    reviewNote: null,
    updatedAt: now,
    authorDisplayName: (article.authorAccountId != null && staffNameOf(article.authorAccountId)) || article.authorDisplayName,
  });
  saveContent(state);
  notifyAuthor(article, 'ARTICLE_REVIEW_APPROVED', `Bài "${article.title}" đã được duyệt và xuất bản.`);
  return ok(view(article), 200, 'Đã duyệt và xuất bản bài viết');
};

const reject: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  const b = readBody(req.body, { text: ['reviewNote'] });
  if (!b) return malformed();
  if (role(req) !== 'SUPER_MANAGER') return denied();
  const bad = invalid([['reviewNote', `Lý do tối đa ${ARTICLEX_LIMITS.reviewNote} ký tự`, tooLong(b.reviewNote, ARTICLEX_LIMITS.reviewNote)]]);
  if (bad) return bad;
  const found = reviewable(req);
  if (!found) return notFound('bài viết', req.params.articleId);
  const { state, article } = found;
  if (!isText(b.reviewNote)) return ruleFail('BR-BV-06', 'Vui lòng nhập lý do từ chối');
  if (article.status !== 'PENDING_REVIEW') return stateError(article.status, 'REJECTED');
  const now = nowIso();
  Object.assign(article, { status: 'REJECTED', reviewNote: norm(b.reviewNote), reviewedBy: me(req).id, reviewedAt: now, updatedAt: now });
  saveContent(state);
  notifyAuthor(article, 'ARTICLE_REVIEW_REJECTED', `Bài "${article.title}" bị từ chối: ${article.reviewNote}`);
  return ok(view(article), 200, 'Đã từ chối bài viết');
};

// ─── Bình luận (#9–14) ───

/** A5: "Nguyễn Thu Trang" → "Nguyễn T."; tên một từ giữ nguyên. */
function shortName(fullName: string): string {
  const words = fullName.trim().split(/\s+/).filter(Boolean);
  if (words.length <= 1) return words[0] ?? 'Khách';
  return `${words[0]} ${[...words[words.length - 1]][0]}.`;
}

const authorOf = (c: CommentRow) => shortName(findCustomer(c.customerId)?.fullName ?? 'Khách');

const publicView = (c: CommentRow): PublicComment => ({ commentId: c.commentId, authorName: authorOf(c), content: c.content, createdAt: c.createdAt, editedAt: c.editedAt });
const commentView = (c: CommentRow): Comment => ({
  commentId: c.commentId,
  articleId: c.articleId,
  content: c.content,
  status: c.status,
  createdAt: c.createdAt,
  editedAt: c.editedAt,
  hideReason: c.hideReason,
});

const isPublished = (a: StoredArticle) => a.status === 'PUBLISHED';

const listComments: MockHandler = (req) => {
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  const article = rowsOf().rows.find((a) => a.slug === req.params.slug && isPublished(a));
  if (!article) return notFound('bài viết', req.params.slug);
  const found = load()
    .comments.filter((c) => c.articleId === article.articleId && c.status === 'ACTIVE')
    .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.commentId - b.commentId);
  return ok(pageOf(found.map(publicView), clampPaging(req.query)));
};

const contentError = (content: unknown): MockReply | null =>
  !isText(content) || length(norm(content)) < 1 || length(norm(content)) > ARTICLEX_LIMITS.comment
    ? ruleFail('BR-BV-08', `Nội dung bình luận phải từ 1 đến ${ARTICLEX_LIMITS.comment} ký tự`)
    : null;

/** Hồ sơ khách của người gọi. */
const customerIdOf = (req: MockRequest) => customerOfAccount(me(req).id)?.customerId ?? null;

const createComment: MockHandler = (req) => {
  const badId = typeError(req.params, ['articleId']);
  if (badId) return badId;
  const b = readBody(req.body, { text: ['content'] });
  if (!b) return malformed();
  if (role(req) !== 'CUSTOMER') return denied();
  const article = rowsOf().rows.find((a) => a.articleId === Number(req.params.articleId));
  if (!article || !isPublished(article)) return notFound('bài viết', req.params.articleId);
  const refused = contentError(b.content);
  if (refused) return refused;
  const customerId = customerIdOf(req);
  if (customerId === null) return notFound('hồ sơ khách của tài khoản', me(req).id);

  const s = load();
  const since = Date.now() - HOUR;
  if (s.comments.filter((c) => c.customerId === customerId && Date.parse(c.createdAt) >= since).length >= ARTICLEX_LIMITS.commentPerHour) {
    return fail('TOO_MANY_REQUESTS', `Bạn đã bình luận quá ${ARTICLEX_LIMITS.commentPerHour} lần trong một giờ, vui lòng thử lại sau`, 429);
  }
  const comment: CommentRow = {
    commentId: ++s.seq,
    articleId: article.articleId,
    customerId,
    content: norm(b.content as string),
    status: 'ACTIVE',
    createdAt: nowIso(),
    editedAt: null,
    hiddenBy: null,
    hideReason: null,
    hiddenAt: null,
  };
  s.comments.push(comment);
  save(s);
  return ok(commentView(comment), 201, 'Đã đăng bình luận');
};

/** Bình luận theo `:commentId`: không có → 404; của khách khác → 403 phạm vi (A4). */
function ownComment(req: MockRequest): { s: State; comment: CommentRow } | MockReply {
  const s = load();
  const comment = s.comments.find((c) => c.commentId === Number(req.params.commentId));
  if (!comment) return notFound('bình luận', req.params.commentId);
  return comment.customerId === customerIdOf(req) ? { s, comment } : scopeDenied();
}
const isOwn = (x: { comment: CommentRow } | MockReply): x is { s: State; comment: CommentRow } => 'comment' in x;

const updateComment: MockHandler = (req) => {
  const badId = typeError(req.params, ['commentId']);
  if (badId) return badId;
  const b = readBody(req.body, { text: ['content'] });
  if (!b) return malformed();
  if (role(req) !== 'CUSTOMER') return denied();
  const found = ownComment(req);
  if (!isOwn(found)) return found;
  const refused = contentError(b.content);
  if (refused) return refused;
  const { s, comment } = found;
  if (comment.status !== 'ACTIVE') return fail('INVALID_STATE_TRANSITION', `Bình luận đã bị ẩn hoặc đã xóa, không sửa được (${comment.status})`, 409);
  Object.assign(comment, { content: norm(b.content as string), editedAt: nowIso() });
  save(s);
  return ok(commentView(comment), 200, 'Đã cập nhật bình luận');
};

const deleteComment: MockHandler = (req) => {
  const badId = typeError(req.params, ['commentId']);
  if (badId) return badId;
  if (role(req) !== 'CUSTOMER') return denied();
  const found = ownComment(req);
  if (!isOwn(found)) return found;
  const { s, comment } = found;
  if (comment.status !== 'DELETED') {
    Object.assign(comment, { status: 'DELETED', hiddenBy: null, hideReason: null, hiddenAt: null });
    save(s);
  }
  return noContent();
};

const hide: MockHandler = (req) => {
  const badId = typeError(req.params, ['commentId']);
  if (badId) return badId;
  const b = readBody(req.body, { text: ['reason'] });
  if (!b) return malformed();
  if (role(req) !== 'SUPER_MANAGER') return denied();
  const bad = invalid([['reason', `Lý do tối đa ${ARTICLEX_LIMITS.hideReason} ký tự`, tooLong(b.reason, ARTICLEX_LIMITS.hideReason)]]);
  if (bad) return bad;
  const s = load();
  const comment = s.comments.find((c) => c.commentId === Number(req.params.commentId));
  if (!comment) return notFound('bình luận', req.params.commentId);
  if (!isText(b.reason)) return ruleFail('BR-BV-08', 'Vui lòng nhập lý do ẩn bình luận');
  if (comment.status !== 'ACTIVE') return fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái bình luận từ ${comment.status} sang HIDDEN`, 409);
  Object.assign(comment, { status: 'HIDDEN', hiddenBy: me(req).id, hideReason: norm(b.reason), hiddenAt: nowIso() });
  save(s);
  return ok(commentView(comment), 200, 'Đã ẩn bình luận');
};

const unhide: MockHandler = (req) => {
  const badId = typeError(req.params, ['commentId']);
  if (badId) return badId;
  if (role(req) !== 'SUPER_MANAGER') return denied();
  const s = load();
  const comment = s.comments.find((c) => c.commentId === Number(req.params.commentId));
  if (!comment) return notFound('bình luận', req.params.commentId);
  if (comment.status !== 'HIDDEN') return fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái bình luận từ ${comment.status} sang ACTIVE`, 409);
  Object.assign(comment, { status: 'ACTIVE', hiddenBy: null, hideReason: null, hiddenAt: null });
  save(s);
  return ok(commentView(comment), 200, 'Đã bỏ ẩn bình luận');
};

export const articlexRoutes: Record<string, MockHandler> = {
  'POST /api/me/articles': createMy,
  'GET /api/me/articles': listMy,
  'GET /api/me/articles/:articleId': getMy,
  'PATCH /api/me/articles/:articleId': updateMy,
  'POST /api/me/articles/:articleId/submit': submit,
  'POST /api/me/articles/:articleId/withdraw': withdraw,
  'POST /api/articles/:articleId/approve': approve,
  'POST /api/articles/:articleId/reject': reject,
  'GET /api/public/articles/:slug/comments': listComments,
  'POST /api/me/articles/:articleId/comments': createComment,
  'PATCH /api/me/comments/:commentId': updateComment,
  'DELETE /api/me/comments/:commentId': deleteComment,
  'POST /api/comments/:commentId/hide': hide,
  'POST /api/comments/:commentId/unhide': unhide,
};
