// Mock nội dung trang tĩnh (T47, UC21): danh sách + sửa 4 trang cho SUPER_MANAGER và trang công khai (content-v1 #1, #2, #14).
// Luật và thông điệp chép từ BE (PageContentService, UpdatePageContentRequest, PageContentController). Thứ tự lỗi như Spring:
// 401 → 400 body (kiểu, Bean Validation; @PreAuthorize chạy sau khi đọc body) → 403 vai trò → 404 khóa → 400 rule (đối tượng JSON, 64 KB).
// State đọc lại từ localStorage ở PAGES_STATE_KEY mỗi request nên trang công khai đổi theo admin; khóa thiếu hoặc hỏng lấy lại seed.
// Path công khai: token gửi kèm bị bỏ qua (ADR-0005).
//
// Các điểm hợp đồng im lặng (ASSUMPTION):
//  - Cấu trúc `content` (Q1) không kiểm ở đây, như BE: mọi JSON object ≤ 64 KB; cấu trúc FE đề xuất ở shared/constants/page-content.ts.
//  - `"content": null` là NullNode của Jackson (không phải null Java) nên rơi vào kiểm "đối tượng JSON", không phải @NotNull; chỉ thiếu
//    hẳn trường `content` mới nhận "Vui lòng nhập nội dung trang".
//  - `title` không phải chuỗi hoặc null (Jackson có thể ép số thành chuỗi) bị coi là body không đọc được; BE không trim `title`.
import { PAGE_CONTENT_MAX_BYTES, PAGE_KEYS, pageContentBytes } from '../../constants/page-content';
import type { PageContent, PageKey } from '../../types/page-content';
import { denied, fail, invalid, notFound, ok, tooLong, type MockHandler } from './envelope';

export const PAGES_STATE_KEY = 'petcare-mock-pages';

/** Lúc BE chạy V21 (cột `updated_at` mặc định); cố định để test không phụ thuộc đồng hồ. */
const SEEDED_AT = '2026-10-01T08:00:00+07:00';

type State = Record<PageKey, PageContent>;

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const seed = (key: PageKey): PageContent => ({ key, title: null, content: {}, updatedAt: SEEDED_AT });

/** Mỗi khóa: bản đã lưu nếu dùng được (có `content` là object), ngược lại seed; trường thiếu của bản cũ lấy từ seed. */
function load(): State {
  let stored: unknown = null;
  try {
    stored = JSON.parse(localStorage.getItem(PAGES_STATE_KEY) ?? 'null');
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const saved = isObject(stored) ? stored : {};
  return Object.fromEntries(
    PAGE_KEYS.map((key) => {
      const record = saved[key];
      return [key, isObject(record) && isObject(record.content) ? { ...seed(key), ...record, key } : seed(key)];
    }),
  ) as State;
}

function save(s: State) {
  try {
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/** Dựng tường minh 4 trường của PageContentResponse (không trải bản ghi để trường lạ của state cũ không lọt ra). */
const view = ({ key, title, content, updatedAt }: PageContent): PageContent => ({ key, title: title ?? null, content, updatedAt });

const keyOf = (raw: string) => PAGE_KEYS.find((k) => k === raw);

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);

/** #1 (@PreAuthorize SUPER_MANAGER): ADMIN kỹ thuật và mọi role khác đều 403. */
const listPages: MockHandler = ({ account }) => {
  if (account!.role !== 'SUPER_MANAGER') return denied();
  const state = load();
  return ok(PAGE_KEYS.map((key) => view(state[key])), 200, 'Danh sách nội dung trang');
};

/** #2: ghi đè hoàn toàn (`title` vắng thành null); 64 KB tính theo byte UTF-8 của JSON gọn, `title` không tính. */
const updatePage: MockHandler = ({ account, params, body }) => {
  if (!isObject(body)) return malformed();
  const { title, content } = body;
  if (title !== undefined && title !== null && typeof title !== 'string') return malformed();
  const bad = invalid([
    ['title', 'Tiêu đề tối đa 250 ký tự', tooLong(title, 250)],
    ['content', 'Vui lòng nhập nội dung trang', content === undefined],
  ]);
  if (bad) return bad;
  if (account!.role !== 'SUPER_MANAGER') return denied();
  const key = keyOf(params.key);
  if (!key) return notFound('trang', params.key);
  if (!isObject(content)) return fail('VALIDATION_FAILED', 'Nội dung trang phải là một đối tượng JSON', 400);
  if (pageContentBytes(content) > PAGE_CONTENT_MAX_BYTES) return fail('VALIDATION_FAILED', 'Nội dung trang tối đa 64 KB', 400);
  const state = load();
  state[key] = { key, title: title ?? null, content, updatedAt: new Date().toISOString() };
  save(state);
  return ok(view(state[key]), 200, 'Đã cập nhật nội dung trang');
};

/** #14, công khai: khóa lạ hoặc chữ thường là 404. */
const getPublicPage: MockHandler = ({ params }) => {
  const key = keyOf(params.key);
  return key ? ok(view(load()[key]), 200, 'Nội dung trang') : notFound('trang', params.key);
};

export const pageContentRoutes: Record<string, MockHandler> = {
  'GET /api/page-contents': listPages,
  'PUT /api/page-contents/:key': updatePage,
  'GET /api/public/pages/:key': getPublicPage,
};
