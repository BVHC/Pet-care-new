import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PAGE_CONTENT_MAX_BYTES, PAGE_KEYS, pageContentBytes } from '../../constants/page-content';
import type { PageContent, PageKey } from '../../types/page-content';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';
import { PAGES_STATE_KEY } from './page-content.mock';

// Dữ liệu mẫu như BE seed (V21): 4 trang HOME_BANNER, ABOUT, POLICY, FAQ, content `{}`, title null.
// Luật chép từ BE (PageContentService, UpdatePageContentRequest); chỗ im lặng ghi ASSUMPTION ở đầu page-content.mock.ts.
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

afterEach(() => {
  vi.useRealTimers();
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const superManager = () => as('chuoi@petcare.vn');

const NON_SUPER_MANAGERS = [
  ['ADMIN kỹ thuật', 'admin@petcare.vn'],
  ['BRANCH_MANAGER', 'manager@store1.vn'],
  ['RECEPTIONIST', 'reception@store1.vn'],
  ['VET', 'doctor@store1.vn'],
  ['CARETAKER', 'caretaker@store1.vn'],
  ['CUSTOMER', 'khach@petcare.vn'],
] as const;

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const list = async (auth: object) => (await client.get('/api/page-contents', auth)).data.data as PageContent[];
const put = (key: string, body: unknown, auth: object) => client.put(`/api/page-contents/${key}`, body, auth);
const publicPage = (key: string, config: object = {}) => client.get(`/api/public/pages/${key}`, config);
const stored = () => JSON.parse(localStorage.getItem(PAGES_STATE_KEY)!) as Record<PageKey, PageContent>;

/** `{ a: <chuỗi> }` có đúng `target` byte UTF-8 khi serialize; `wide` chữ 'ế' (3 byte) + ASCII cho đủ. `{"a":""}` là 8 byte. */
function contentOfBytes(target: number, wide = 0) {
  const content = { a: 'ế'.repeat(wide) + 'x'.repeat(target - 8 - wide * 3) };
  expect(pageContentBytes(content)).toBe(target);
  return content;
}

describe('GET /api/page-contents (content-v1 #1, UC21)', () => {
  it('SUPER_MANAGER: 4 trang theo thứ tự HOME_BANNER, ABOUT, POLICY, FAQ, đúng 4 trường, content {} và title null như BE seed', async () => {
    const res = await client.get('/api/page-contents', await superManager());
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Danh sách nội dung trang');
    const pages = res.data.data as PageContent[];
    expect(pages.map((p) => p.key)).toEqual(['HOME_BANNER', 'ABOUT', 'POLICY', 'FAQ']);
    expect(pages.map((p) => p.key)).toEqual([...PAGE_KEYS]);
    for (const page of pages) {
      expect(Object.keys(page).sort()).toEqual(['content', 'key', 'title', 'updatedAt']);
      expect(page).toMatchObject({ title: null, content: {} });
      expect(Number.isNaN(Date.parse(page.updatedAt))).toBe(false);
    }
  });

  it.each(NON_SUPER_MANAGERS)('%s: 403 ACCESS_DENIED (chỉ SUPER_MANAGER, không có RoleHierarchy)', async (_role, email) => {
    const res = await failure(client.get('/api/page-contents', await as(email)));
    expect(res.status).toBe(403);
    expect(res.data.errorCode).toBe('ACCESS_DENIED');
  });

  it('chưa đăng nhập: 401 UNAUTHENTICATED', async () => {
    const res = await failure(client.get('/api/page-contents'));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
  });
});

describe('PUT /api/page-contents/:key (content-v1 #2, UC21)', () => {
  it('lưu title + content, trả đúng 4 trường kèm updatedAt mới; GET danh sách thấy ngay, các trang khác không đổi', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-26T03:00:00Z'));
    const auth = await superManager();
    const before = await list(auth);
    const body = { title: 'Về chúng tôi', content: { sections: [{ heading: 'Sứ mệnh', body: 'Chăm sóc thú cưng' }] } };

    const res = await put('ABOUT', body, auth);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã cập nhật nội dung trang');
    expect(res.data.data).toEqual({ key: 'ABOUT', ...body, updatedAt: '2026-10-26T03:00:00.000Z' });
    expect(Date.parse(res.data.data.updatedAt)).toBeGreaterThan(Date.parse(before[1].updatedAt));

    const after = await list(auth);
    expect(after.map((p) => p.key)).toEqual([...PAGE_KEYS]);
    expect(after[1]).toEqual(res.data.data);
    expect(after.filter((p) => p.key !== 'ABOUT')).toEqual(before.filter((p) => p.key !== 'ABOUT'));
  });

  it('ghi đè hoàn toàn, không trộn: PUT {a} rồi PUT {b} thì a biến mất; title vắng thì thành null', async () => {
    const auth = await superManager();
    await put('FAQ', { title: 'Hỏi đáp', content: { a: 1 } }, auth);
    expect((await list(auth))[3]).toMatchObject({ title: 'Hỏi đáp', content: { a: 1 } });

    const res = await put('FAQ', { content: { b: 2 } }, auth);
    expect(res.data.data).toMatchObject({ title: null, content: { b: 2 } });
    expect((await list(auth))[3]).toMatchObject({ title: null, content: { b: 2 } });
    expect((await list(auth))[3].content).not.toHaveProperty('a');
    // title null tường minh cũng là null; content rỗng {} là hợp lệ (xóa sạch về mặc định phía FE).
    expect((await put('FAQ', { title: null, content: {} }, auth)).data.data).toMatchObject({ title: null, content: {} });
  });

  it('trang công khai thấy ngay bản vừa lưu, không cần đăng nhập, token sai bị bỏ qua (ADR-0005)', async () => {
    const auth = await superManager();
    await put('POLICY', { title: 'Chính sách', content: { terms: { lead: 'x' } } }, auth);
    for (const config of [{}, { headers: { Authorization: 'Bearer het-han' } }]) {
      const res = await publicPage('POLICY', config);
      expect(res.status).toBe(200);
      expect(res.data.message).toBe('Nội dung trang');
      expect(res.data.data).toMatchObject({ key: 'POLICY', title: 'Chính sách', content: { terms: { lead: 'x' } } });
      expect(Object.keys(res.data.data).sort()).toEqual(['content', 'key', 'title', 'updatedAt']);
    }
  });

  describe('quyền và thứ tự lỗi: 401 → 400 body → 403 vai trò → 404 khóa → 400 rule', () => {
    it.each(NON_SUPER_MANAGERS)('%s: 403, không ghi gì', async (_role, email) => {
      const res = await failure(put('FAQ', { content: { x: 1 } }, await as(email)));
      expect(res.status).toBe(403);
      expect(res.data.errorCode).toBe('ACCESS_DENIED');
      expect((await list(await superManager()))[3].content).toEqual({});
      expect(localStorage.getItem(PAGES_STATE_KEY)).toBeNull();
    });

    it('chưa đăng nhập: 401', async () => {
      expect((await failure(client.put('/api/page-contents/FAQ', { content: {} }))).status).toBe(401);
    });

    it('body sai (title quá 250, thiếu content) đến trước 403: CUSTOMER gửi title 251 ký tự vẫn nhận 400', async () => {
      const customer = await as('khach@petcare.vn');
      const long = await failure(put('FAQ', { title: 't'.repeat(251), content: {} }, customer));
      expect(long.status).toBe(400);
      expect(long.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'title: Tiêu đề tối đa 250 ký tự' });
      const missing = await failure(put('FAQ', { title: 'x' }, customer));
      expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'content: Vui lòng nhập nội dung trang' });
      expect((await failure(put('FAQ', {}, customer))).data.message).toBe('content: Vui lòng nhập nội dung trang');
    });

    it('403 đến trước 404: người không có quyền gửi body hợp lệ tới khóa lạ vẫn nhận 403', async () => {
      const res = await failure(put('KHONG-CO', { content: {} }, await as('manager@store1.vn')));
      expect(res.status).toBe(403);
    });

    it('khóa lạ hoặc chữ thường: 404 RESOURCE_NOT_FOUND "Không tìm thấy trang #<khóa>", cả ở PUT lẫn trang công khai', async () => {
      const auth = await superManager();
      for (const key of ['NOPE', 'about', 'Faq', 'FAQ%20']) {
        const res = await failure(put(key, { content: {} }, auth));
        expect(res.status, key).toBe(404);
        expect(res.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: `Không tìm thấy trang #${decodeURIComponent(key)}` });
      }
      for (const key of ['abc', 'about', 'HOME_BANNER%20']) {
        expect((await failure(publicPage(key))).status, key).toBe(404);
      }
      // 404 đến trước kiểm "đối tượng JSON" / 64 KB (BE parse khóa trước).
      expect((await failure(put('NOPE', { content: [] }, auth))).status).toBe(404);
      expect((await failure(put('NOPE', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES + 1) }, auth))).status).toBe(404);
    });

    it('body không phải JSON object, hoặc title sai kiểu: 400 MALFORMED_REQUEST', async () => {
      const auth = await superManager();
      for (const body of [[], 'abc', 5, { title: { a: 1 }, content: {} }, { title: ['x'], content: {} }]) {
        const res = await failure(put('FAQ', body, auth));
        expect(res.status).toBe(400);
        expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
      }
    });
  });

  describe('content phải là JSON object (BR: "Nội dung trang phải là một đối tượng JSON")', () => {
    it.each([[[]], ['x'], [5], [true], [[{ a: 1 }]]])('content = %j: 400 VALIDATION_FAILED, không ghi gì', async (content) => {
      const auth = await superManager();
      const res = await failure(put('FAQ', { content }, auth));
      expect(res.status).toBe(400);
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Nội dung trang phải là một đối tượng JSON' });
      expect((await list(auth))[3].content).toEqual({});
    });

    // ASSUMPTION: Jackson đọc `"content": null` thành NullNode (không phải null Java) nên rơi vào kiểm "đối tượng JSON"; test chỉ khóa mã lỗi.
    it('content = null: 400 VALIDATION_FAILED', async () => {
      const res = await failure(put('FAQ', { content: null }, await superManager()));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    });
  });

  describe('giới hạn 64 KB = byte UTF-8 của JSON đã serialize, không phải số ký tự (PageContentService.MAX_CONTENT_BYTES)', () => {
    it('đúng 65.536 byte thì nhận; thêm 1 byte thì 400 "Nội dung trang tối đa 64 KB" (ASCII)', async () => {
      const auth = await superManager();
      expect((await put('FAQ', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES) }, auth)).status).toBe(200);
      const res = await failure(put('FAQ', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES + 1) }, auth));
      expect(res.status).toBe(400);
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Nội dung trang tối đa 64 KB' });
    });

    it('tiếng Việt có dấu (3 byte/ký tự): biên 65.536 / 65.537 byte tính theo byte', async () => {
      const auth = await superManager();
      expect((await put('ABOUT', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES, 21_842) }, auth)).status).toBe(200);
      const res = await failure(put('ABOUT', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES + 1, 21_842) }, auth));
      expect(res.data.message).toBe('Nội dung trang tối đa 64 KB');
    });

    it('ít hơn 64K ký tự nhưng quá 64 KB byte vẫn bị từ chối (đếm ký tự là sai)', async () => {
      const content = { a: 'ế'.repeat(21_846) };
      expect(JSON.stringify(content).length).toBeLessThan(PAGE_CONTENT_MAX_BYTES);
      expect(pageContentBytes(content)).toBeGreaterThan(PAGE_CONTENT_MAX_BYTES);
      const res = await failure(put('POLICY', { content }, await superManager()));
      expect(res.status).toBe(400);
      expect(res.data.message).toBe('Nội dung trang tối đa 64 KB');
    });

    it('title không tính vào 64 KB: content đúng hạn mức kèm title 250 chữ có dấu vẫn nhận', async () => {
      const res = await put('FAQ', { title: 'ế'.repeat(250), content: contentOfBytes(PAGE_CONTENT_MAX_BYTES) }, await superManager());
      expect(res.status).toBe(200);
    });

    it('quá hạn thì không ghi: nội dung cũ còn nguyên', async () => {
      const auth = await superManager();
      await put('FAQ', { content: { giu: 'lại' } }, auth);
      await failure(put('FAQ', { content: contentOfBytes(PAGE_CONTENT_MAX_BYTES + 1) }, auth));
      expect((await list(auth))[3].content).toEqual({ giu: 'lại' });
    });
  });
});

describe('GET /api/public/pages/:key (content-v1 #14, UC15–UC17)', () => {
  it('không cần đăng nhập; seed: content {} và title null (FE tự dùng mặc định)', async () => {
    for (const key of PAGE_KEYS) {
      const res = await publicPage(key);
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({ key, title: null, content: {} });
    }
  });
});

describe('state localStorage petcare-mock-pages: đọc lại ở mọi request, gộp mặc định khi state cũ thiếu', () => {
  it('lưu vào localStorage; sửa thẳng state thì request kế tiếp thấy ngay (trang công khai đổi theo admin)', async () => {
    await put('ABOUT', { content: { v: 1 } }, await superManager());
    expect(stored().ABOUT.content).toEqual({ v: 1 });
    const state = stored();
    state.ABOUT = { ...state.ABOUT, content: { v: 2 } };
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(state));
    expect((await publicPage('ABOUT')).data.data.content).toEqual({ v: 2 });
  });

  it('state hình dạng cũ (chỉ có vài khóa): không throw, khóa thiếu lấy seed, khóa có còn nguyên', async () => {
    const old = { key: 'ABOUT', title: 'Cũ', content: { x: 1 }, updatedAt: '2026-01-01T00:00:00Z' };
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify({ ABOUT: old }));
    const pages = await list(await superManager());
    expect(pages.map((p) => p.key)).toEqual([...PAGE_KEYS]);
    expect(pages[1]).toEqual(old);
    for (const page of pages.filter((p) => p.key !== 'ABOUT')) expect(page).toMatchObject({ title: null, content: {} });
    // Ghi tiếp trên state cũ: khóa khác vẫn được seed và khóa cũ không mất.
    await put('FAQ', { content: { y: 2 } }, await superManager());
    expect(stored().ABOUT).toEqual(old);
    expect(Object.keys(stored()).sort()).toEqual([...PAGE_KEYS].sort());
  });

  it('bản ghi thiếu title / updatedAt thì bù seed, trường lạ không lọt ra response', async () => {
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify({ FAQ: { content: { z: 3 }, noiBo: 'x' } }));
    const faq = (await list(await superManager()))[3];
    expect(Object.keys(faq).sort()).toEqual(['content', 'key', 'title', 'updatedAt']);
    expect(faq).toMatchObject({ key: 'FAQ', title: null, content: { z: 3 } });
    expect(Number.isNaN(Date.parse(faq.updatedAt))).toBe(false);
  });

  it.each([
    ['không phải JSON', '{không phải JSON'],
    ['null', 'null'],
    ['mảng', '[1,2]'],
    ['số', '5'],
    ['khóa trỏ tới null / chuỗi / content sai kiểu', JSON.stringify({ HOME_BANNER: null, ABOUT: 'x', POLICY: { content: [] }, FAQ: { content: 5 } })],
  ])('state hỏng (%s): dùng lại seed, không throw', async (_name, raw) => {
    localStorage.setItem(PAGES_STATE_KEY, raw);
    const pages = await list(await superManager());
    expect(pages.map((p) => p.key)).toEqual([...PAGE_KEYS]);
    for (const page of pages) expect(page).toMatchObject({ title: null, content: {} });
  });
});
