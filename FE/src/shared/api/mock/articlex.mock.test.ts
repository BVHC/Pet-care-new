import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse } from '../../types';
import type { Comment, MyArticle, MyArticleSummary, PublicComment } from '../../types/articlex';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu: bài 1–10 của content-v1 (8 PUBLISHED, 1 DRAFT, 1 HIDDEN, của SUPER_MANAGER); bài mẫu của bác sĩ (id 11–16) chỉ có sau khi một route /me/articles… hoặc hàng chờ duyệt
// được dùng lần đầu: BS. Minh Anh (doctor@, id 5): 11 DRAFT, 12 PENDING_REVIEW, 13 REJECTED, 14 PUBLISHED; BS. Quốc Bảo (doctor2@, id 21): 15 PENDING_REVIEW, 16 HIDDEN.
// Bình luận mẫu: bài 1 có #1 (khach@), #2 (hồ sơ quầy 4, đã sửa) hiện, #3 ẩn, #5 đã xóa; bài 2 có #4; bài 14 có #6.
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
const vet = () => as('doctor@store1.vn');
const vet2 = () => as('doctor2@store1.vn');
const sm = () => as('chuoi@petcare.vn');
const customer = () => as('khach@petcare.vn');
const customer2 = () => as('khachmoi@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const SLUG_1 = 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung';
const SLUG_2 = 'meo-bo-an-3-ngay-khi-nao-can-cap-cuu';
const SLUG_14 = 'lich-tiem-phong-co-ban-cho-cho-con-trong-nam-dau';

type Page<T> = PageResponse<T>;
const article = (res: AxiosResponse) => res.data.data as MyArticle;
const comment = (res: AxiosResponse) => res.data.data as Comment;
const create = async (auth: object, body: object = {}) => article(await client.post('/api/me/articles', { title: 'Bài thử của bác sĩ', content: '<p>Nội dung thử.</p>', ...body }, auth));
const mineList = async (auth: object, params: object = {}) => (await client.get('/api/me/articles', { ...auth, params })).data.data as Page<MyArticleSummary>;
const publicComments = async (slug: string, params: object = {}) => (await client.get(`/api/public/articles/${slug}/comments`, { params })).data.data as Page<PublicComment>;
const adminList = async (params: object) => (await client.get('/api/articles', { ...(await sm()), params })).data.data as Page<{ articleId: number; status: string }>;
const notificationTypes = async (auth: object) => ((await client.get('/api/me/notifications', auth)).data.data.content as { type: string }[]).map((n) => n.type);

/** Gửi duyệt xong một bài mới (chuyên mục 2). */
async function pending(auth: object, body: object = {}) {
  const made = await create(auth, { categoryId: 2, ...body });
  return article(await client.post(`/api/me/articles/${made.articleId}/submit`, undefined, auth));
}

describe('nạp module không đổi bài viết cũ', () => {
  it('chưa gọi route nào: trang công khai và danh sách quản trị của content-v1 giữ nguyên số liệu GĐ1', async () => {
    expect(localStorage.length).toBe(0);
    expect((await client.get('/api/public/articles')).data.data.totalElements).toBe(8);
    expect((await adminList({})).totalElements).toBe(10);
    expect((await adminList({ status: 'DRAFT' })).totalElements).toBe(1);
  });
});

// ─── Quyền ───

const ALL_ROLES: [string, string][] = [
  ['ADMIN', 'admin@petcare.vn'],
  ['SUPER_MANAGER', 'chuoi@petcare.vn'],
  ['BRANCH_MANAGER', 'manager@store1.vn'],
  ['RECEPTIONIST', 'reception@store1.vn'],
  ['VET', 'doctor@store1.vn'],
  ['CARETAKER', 'caretaker@store1.vn'],
  ['CUSTOMER', 'khach@petcare.vn'],
];
type Call = (auth: object) => Promise<unknown>;
const ENDPOINTS: [name: string, allowed: string, call: Call][] = [
  ['#1 POST /me/articles', 'VET', (a) => client.post('/api/me/articles', { title: 'T', content: '<p>x</p>' }, a)],
  ['#2 GET /me/articles', 'VET', (a) => client.get('/api/me/articles', a)],
  ['#3 GET /me/articles/{id}', 'VET', (a) => client.get('/api/me/articles/99999', a)],
  ['#4 PATCH /me/articles/{id}', 'VET', (a) => client.patch('/api/me/articles/99999', {}, a)],
  ['#5 POST /me/articles/{id}/submit', 'VET', (a) => client.post('/api/me/articles/99999/submit', undefined, a)],
  ['#6 POST /me/articles/{id}/withdraw', 'VET', (a) => client.post('/api/me/articles/99999/withdraw', undefined, a)],
  ['#7 POST /articles/{id}/approve', 'SUPER_MANAGER', (a) => client.post('/api/articles/99999/approve', undefined, a)],
  ['#8 POST /articles/{id}/reject', 'SUPER_MANAGER', (a) => client.post('/api/articles/99999/reject', { reviewNote: 'x' }, a)],
  ['#10 POST /me/articles/{id}/comments', 'CUSTOMER', (a) => client.post('/api/me/articles/99999/comments', { content: 'x' }, a)],
  ['#11 PATCH /me/comments/{id}', 'CUSTOMER', (a) => client.patch('/api/me/comments/99999', { content: 'x' }, a)],
  ['#12 DELETE /me/comments/{id}', 'CUSTOMER', (a) => client.delete('/api/me/comments/99999', a)],
  ['#13 POST /comments/{id}/hide', 'SUPER_MANAGER', (a) => client.post('/api/comments/99999/hide', { reason: 'x' }, a)],
  ['#14 POST /comments/{id}/unhide', 'SUPER_MANAGER', (a) => client.post('/api/comments/99999/unhide', undefined, a)],
];

describe.each(ENDPOINTS)('quyền %s', (_name, allowed, call) => {
  it.each(ALL_ROLES.filter(([role]) => role !== allowed))('%s → 403 ACCESS_DENIED', async (_role, email) => {
    const res = await failure(call(await as(email)));
    expect(res.status).toBe(403);
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403 });
  });

  it(`${allowed} qua cổng quyền (id không có thì 404, không phải 403)`, async () => {
    const email = ALL_ROLES.find(([role]) => role === allowed)![1];
    const res = await call(await as(email)).then((r) => r as AxiosResponse, (e: { response: AxiosResponse }) => e.response);
    expect([200, 201, 204, 404]).toContain(res.status);
  });

  it('không token → 401', async () => {
    expect((await failure(call({}))).status).toBe(401);
  });
});

describe('#9 GET /public/articles/{slug}/comments công khai', () => {
  it('không cần token, token gửi kèm bị bỏ qua', async () => {
    expect((await client.get(`/api/public/articles/${SLUG_1}/comments`)).status).toBe(200);
    expect((await client.get(`/api/public/articles/${SLUG_1}/comments`, await customer())).status).toBe(200);
    expect((await client.get(`/api/public/articles/${SLUG_1}/comments`, { headers: { Authorization: 'Bearer hết-hạn' } })).status).toBe(200);
  });
});

// ─── Bài viết của tôi (#1–6) ───

describe('soạn bài (#1)', () => {
  it('201 DRAFT của chính bác sĩ: slug sinh từ tiêu đề, tên tác giả theo hồ sơ VET, HTML được lọc', async () => {
    const res = await client.post(
      '/api/me/articles',
      { title: '  Đừng cho mèo ăn hành tỏi!  ', categoryId: 2, summary: ' Tóm tắt ', content: '<p>Hành tỏi độc với mèo.</p><script>alert(1)</script>', coverImageUrl: 'https://example.com/a.jpg' },
      await vet(),
    );
    expect(res.status).toBe(201);
    expect(article(res)).toMatchObject({
      title: 'Đừng cho mèo ăn hành tỏi!',
      slug: 'dung-cho-meo-an-hanh-toi',
      categoryId: 2,
      summary: 'Tóm tắt',
      content: '<p>Hành tỏi độc với mèo.</p>',
      coverImageUrl: 'https://example.com/a.jpg',
      status: 'DRAFT',
      reviewNote: null,
      submittedAt: null,
      publishedAt: null,
    });
    expect(Date.parse(article(res).updatedAt)).not.toBeNaN();
    // Nháp của bác sĩ không lộ ra ngoài: SUPER_MANAGER và trang công khai không thấy.
    const id = article(res).articleId;
    expect((await failure(client.get(`/api/articles/${id}`, await sm()))).status).toBe(404);
    expect((await adminList({})).content.map((a) => a.articleId)).not.toContain(id);
    expect((await failure(client.get('/api/public/articles/dung-cho-meo-an-hanh-toi'))).status).toBe(404);
  });

  it('chuyên mục không bắt buộc ở bản nháp; tên tác giả của bác sĩ thứ hai', async () => {
    const mine = await create(await vet2());
    expect(mine.categoryId ?? null).toBeNull();
    const row = await client.get(`/api/me/articles/${mine.articleId}`, await vet2());
    expect(row.data.data.title).toBe('Bài thử của bác sĩ');
  });

  it('cùng tiêu đề thì slug thêm -2, -3', async () => {
    const auth = await vet();
    const slugs = [await create(auth), await create(auth), await create(auth)].map((a) => a.slug);
    expect(slugs).toEqual(['bai-thu-cua-bac-si', 'bai-thu-cua-bac-si-2', 'bai-thu-cua-bac-si-3']);
  });

  it('thứ tự lỗi: sai kiểu (400) → 403 vai trò → VALIDATION_FAILED → BR-BV-01', async () => {
    const reception = await as('reception@store1.vn');
    expect((await failure(client.post('/api/me/articles', { title: 5, content: 'x' }, reception))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/me/articles', { title: 'T', content: 'x', categoryId: 'hai' }, reception))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/me/articles', [], await vet()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/me/articles', {}, reception))).data.errorCode).toBe('ACCESS_DENIED');

    const auth = await vet();
    const missing = await failure(client.post('/api/me/articles', { title: 'Có tiêu đề' }, auth));
    expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    expect(missing.data.message).toContain('content');
    expect((await failure(client.post('/api/me/articles', { title: 'T', content: 'x', summary: 's'.repeat(501) }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/me/articles', { title: 'T', content: 'x', coverImageUrl: 'http://example.com/a.jpg' }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/me/articles', { title: 'T', content: 'x', coverImageUrl: '' }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    // Validation chạy trước BR-BV-01: thiếu nội dung và tiêu đề trống thì báo thiếu nội dung.
    expect((await failure(client.post('/api/me/articles', { title: ' ' }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('BR-BV-01: tiêu đề rỗng / quá 200 ký tự, chuyên mục không tồn tại (400, không phải 404), nội dung trống sau khi lọc', async () => {
    const auth = await vet();
    const rule = async (body: object) => {
      const res = await failure(client.post('/api/me/articles', { content: '<p>x</p>', ...body }, auth));
      expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
      expect(res.data.message).toMatch(/\(BR-BV-01\)$/);
    };
    await rule({ title: '   ' });
    await rule({ title: 'x'.repeat(201) });
    await rule({ title: 'Có tiêu đề', categoryId: 999 });
    await rule({ title: 'Có tiêu đề', content: '<script>alert(1)</script>' });
    expect((await client.post('/api/me/articles', { title: 'x'.repeat(200), content: '<p>x</p>' }, auth)).status).toBe(201);
  });
});

describe('bài viết của tôi (#2, #3)', () => {
  it('chỉ thấy bài của mình, mới sửa trước; lọc status; phân trang', async () => {
    const auth = await vet();
    const first = await mineList(auth);
    expect(first.content.map((a) => a.articleId).sort()).toEqual([11, 12, 13, 14]);
    const times = first.content.map((a) => Date.parse(a.updatedAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    const made = await create(auth);
    expect((await mineList(auth)).content[0].articleId).toBe(made.articleId);
    expect((await mineList(auth, { status: 'PENDING_REVIEW' })).content.map((a) => a.articleId)).toEqual([12]);
    expect((await mineList(auth, { status: 'REJECTED' })).content[0]).toMatchObject({ articleId: 13, status: 'REJECTED' });
    expect((await mineList(auth, { size: 2, page: 1 }))).toMatchObject({ page: 1, size: 2, totalElements: 5, totalPages: 3 });
    expect((await mineList(await vet2())).content.map((a) => a.articleId).sort()).toEqual([15, 16]);
    expect(first.content.find((a) => a.articleId === 14)).toMatchObject({ status: 'PUBLISHED', publishedAt: expect.any(String) });
  });

  it('kẹp page, size về khoảng hợp lệ; sai kiểu → 400 trước cả cổng quyền', async () => {
    const auth = await vet();
    expect(await mineList(auth, { page: -1, size: 1000 })).toMatchObject({ page: 0, size: 100 });
    expect(await mineList(auth, { size: 0 })).toMatchObject({ size: 1 });
    expect((await failure(client.get('/api/me/articles', { ...auth, params: { status: 'ARCHIVED' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/me/articles', { ...(await customer()), params: { page: 'x' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('chi tiết: bài của mình đủ nội dung; bài của người khác và bài lạ cùng 404', async () => {
    const auth = await vet();
    const own = article(await client.get('/api/me/articles/13', auth));
    expect(own).toMatchObject({ articleId: 13, status: 'REJECTED', reviewNote: expect.stringContaining('dẫn nguồn'), content: expect.stringContaining('<p>') });
    expect((await failure(client.get('/api/me/articles/13', await vet2()))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404 });
    expect((await failure(client.get('/api/me/articles/6', auth))).status).toBe(404); // bài của SUPER_MANAGER
    expect((await failure(client.get('/api/me/articles/abc', auth))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('sửa bài (#4)', () => {
  it('sửa DRAFT: đổi từng trường, đổi tiêu đề thì sinh lại slug, không đổi gì thì giữ updatedAt', async () => {
    const auth = await vet();
    const made = await create(auth);
    const same = article(await client.patch(`/api/me/articles/${made.articleId}`, {}, auth));
    expect(same.updatedAt).toBe(made.updatedAt);
    const edited = article(await client.patch(`/api/me/articles/${made.articleId}`, { title: 'Tiêu đề mới', categoryId: 3, summary: 'Tóm tắt mới', content: '<p>Mới</p>', coverImageUrl: 'https://example.com/b.png' }, auth));
    expect(edited).toMatchObject({ title: 'Tiêu đề mới', slug: 'tieu-de-moi', categoryId: 3, summary: 'Tóm tắt mới', content: '<p>Mới</p>', status: 'DRAFT' });
    // null giữ nguyên.
    expect(article(await client.patch(`/api/me/articles/${made.articleId}`, { title: null, content: null }, auth))).toMatchObject({ title: 'Tiêu đề mới', content: '<p>Mới</p>' });
  });

  it('sửa bài bị từ chối đưa về DRAFT và xóa lý do từ chối (Bài viết#6)', async () => {
    const auth = await vet();
    const fixed = article(await client.patch('/api/me/articles/13', { summary: 'Đã bổ sung nguồn tham khảo.' }, auth));
    expect(fixed).toMatchObject({ articleId: 13, status: 'DRAFT', reviewNote: null, summary: 'Đã bổ sung nguồn tham khảo.' });
    expect(article(await client.get('/api/me/articles/13', auth)).reviewNote ?? null).toBeNull();
  });

  it('409 BR-BV-07 khi đang chờ duyệt hoặc đã xuất bản / ẩn', async () => {
    const auth = await vet();
    const waiting = await failure(client.patch('/api/me/articles/12', { title: 'Sửa khi chờ duyệt' }, auth));
    expect(waiting.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    expect(waiting.data.message).toMatch(/rút về nháp.*\(BR-BV-07\)$/);
    const live = await failure(client.patch('/api/me/articles/14', { title: 'Sửa bài đã đăng' }, auth));
    expect(live.data.message).toMatch(/ẩn bài.*\(BR-BV-07\)$/);
    expect((await failure(client.patch('/api/me/articles/16', {}, await vet2()))).data.message).toMatch(/\(BR-BV-07\)$/);
    expect(article(await client.get('/api/me/articles/12', auth)).title).toBe('Dấu hiệu mèo bị viêm đường tiết niệu cần đi khám sớm');
  });

  it('thứ tự lỗi: sai kiểu → 403 → VALIDATION_FAILED → 404 → BR-BV-01 → 409', async () => {
    const auth = await vet();
    expect((await failure(client.patch('/api/me/articles/abc', {}, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.patch('/api/me/articles/11', { summary: 5 }, await customer()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.patch('/api/me/articles/11', {}, await customer()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.patch('/api/me/articles/11', { content: '   ' }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.patch('/api/me/articles/999', { summary: 's'.repeat(501) }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.patch('/api/me/articles/999', { title: ' ' }, auth))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    expect((await failure(client.patch('/api/me/articles/11', { title: ' ' }, await vet2()))).status).toBe(404);
    expect((await failure(client.patch('/api/me/articles/11', { title: ' ' }, auth))).data.message).toMatch(/\(BR-BV-01\)$/);
    expect((await failure(client.patch('/api/me/articles/11', { categoryId: 999 }, auth))).data.message).toMatch(/Chuyên mục.*\(BR-BV-01\)$/);
    // BR-BV-01 chạy trước 409: bài đang chờ duyệt mà tiêu đề rỗng thì báo BR-BV-01.
    expect((await failure(client.patch('/api/me/articles/12', { title: ' ' }, auth))).data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
  });
});

describe('gửi duyệt và rút lại (#5, #6)', () => {
  it('DRAFT → PENDING_REVIEW có submittedAt; SUPER_MANAGER nhận thông báo; rút lại về DRAFT', async () => {
    const auth = await vet();
    const made = await create(auth, { categoryId: 2 });
    const submitted = article(await client.post(`/api/me/articles/${made.articleId}/submit`, undefined, auth));
    expect(submitted).toMatchObject({ status: 'PENDING_REVIEW' });
    expect(Date.parse(submitted.submittedAt!)).not.toBeNaN();
    expect(await notificationTypes(await sm())).toContain('ARTICLE_REVIEW_SUBMITTED');
    expect((await adminList({ status: 'PENDING_REVIEW' })).content.map((a) => a.articleId)).toContain(made.articleId);

    const back = article(await client.post(`/api/me/articles/${made.articleId}/withdraw`, undefined, auth));
    expect(back).toMatchObject({ status: 'DRAFT', submittedAt: null });
    expect((await adminList({ status: 'PENDING_REVIEW' })).content.map((a) => a.articleId)).not.toContain(made.articleId);
    // Rút về nháp rồi sửa được.
    expect((await client.patch(`/api/me/articles/${made.articleId}`, { title: 'Sửa sau khi rút' }, auth)).status).toBe(200);
  });

  it('BR-BV-01 khi thiếu chuyên mục; 409 khi không phải DRAFT (kể cả bài bị từ chối: phải sửa trước)', async () => {
    const auth = await vet();
    const noCategory = await create(auth);
    const res = await failure(client.post(`/api/me/articles/${noCategory.articleId}/submit`, undefined, auth));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION' });
    expect(res.data.message).toMatch(/chuyên mục.*\(BR-BV-01\)$/);
    for (const id of [12, 13, 14]) {
      const conflict = await failure(client.post(`/api/me/articles/${id}/submit`, undefined, auth));
      expect(conflict.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    }
    expect((await failure(client.post('/api/me/articles/999/submit', undefined, auth))).status).toBe(404);
    expect((await failure(client.post('/api/me/articles/12/submit', undefined, await vet2()))).status).toBe(404);
    expect((await failure(client.post('/api/me/articles/x/submit', undefined, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('rút lại: chỉ bài đang chờ duyệt, chỉ của mình', async () => {
    const auth = await vet();
    for (const id of [11, 13, 14]) expect((await failure(client.post(`/api/me/articles/${id}/withdraw`, undefined, auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/me/articles/12/withdraw', undefined, await vet2()))).status).toBe(404);
    expect(article(await client.post('/api/me/articles/12/withdraw', undefined, auth)).status).toBe('DRAFT');
  });
});

// ─── Duyệt bài (#7, #8) ───

describe('hàng chờ duyệt của content-v1 (GET /articles?status=…)', () => {
  it('lọc PENDING_REVIEW / REJECTED; nháp của bác sĩ không lộ; chi tiết đủ nội dung để duyệt', async () => {
    expect((await adminList({ status: 'PENDING_REVIEW' })).content.map((a) => a.articleId).sort()).toEqual([12, 15]);
    expect((await adminList({ status: 'REJECTED' })).content.map((a) => a.articleId)).toEqual([13]);
    const all = (await adminList({ size: 100 })).content.map((a) => a.articleId);
    expect(all).toEqual(expect.arrayContaining([12, 13, 14, 15, 16]));
    expect(all).not.toContain(11);
    expect((await failure(client.get('/api/articles/11', await sm()))).status).toBe(404);
    const detail = (await client.get('/api/articles/12', await sm())).data.data;
    expect(detail).toMatchObject({ articleId: 12, status: 'PENDING_REVIEW', authorDisplayName: 'BS. Minh Anh', content: expect.stringContaining('tắc nghẽn') });
    expect((await failure(client.get('/api/articles', { ...(await sm()), params: { status: 'ARCHIVED' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('duyệt (#7)', () => {
  it('PENDING_REVIEW → PUBLISHED: ghi ngày xuất bản, hiện ở trang công khai cùng bài cũ, thông báo cho bác sĩ', async () => {
    const res = await client.post('/api/articles/12/approve', undefined, await sm());
    expect(res.status).toBe(200);
    expect(article(res)).toMatchObject({ articleId: 12, status: 'PUBLISHED', reviewNote: null, slug: 'dau-hieu-meo-viem-duong-tiet-nieu-can-di-kham-som' });
    expect(Date.parse(article(res).publishedAt!)).not.toBeNaN();

    const list = (await client.get('/api/public/articles', { params: { size: 100 } })).data.data as Page<{ articleId: number; title: string; authorDisplayName: string }>;
    expect(list.totalElements).toBe(10); // 8 bài cũ + bài mẫu 14 + bài vừa duyệt
    expect(list.content.map((a) => a.articleId)).toEqual(expect.arrayContaining([1, 12, 14]));
    const detail = (await client.get(`/api/public/articles/${article(res).slug}`)).data.data;
    expect(detail).toMatchObject({ articleId: 12, authorDisplayName: 'BS. Minh Anh', categoryName: 'Sức khỏe thú y', content: expect.stringContaining('<p>') });
    expect(await notificationTypes(await vet())).toContain('ARTICLE_REVIEW_APPROVED');
    expect(article(await client.get('/api/me/articles/12', await vet())).status).toBe('PUBLISHED');
  });

  it('409 khi không còn chờ duyệt (duyệt lại, bài đã đăng, nháp, bài bị từ chối); nháp của bác sĩ là 404; id lạ 404', async () => {
    const auth = await sm();
    await client.post('/api/articles/12/approve', undefined, auth);
    for (const id of [12, 13, 14, 16, 6]) {
      const res = await failure(client.post(`/api/articles/${id}/approve`, undefined, auth));
      expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    }
    expect((await failure(client.post('/api/articles/11/approve', undefined, auth))).status).toBe(404);
    expect((await failure(client.post('/api/articles/999/approve', undefined, auth))).status).toBe(404);
    expect((await failure(client.post('/api/articles/abc/approve', undefined, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('bài vừa gửi duyệt đi hết chu trình: gửi → duyệt → trang công khai → ẩn (content-v1) → mở lại', async () => {
    const author = await vet2();
    const made = await pending(author, { title: 'Chu trình duyệt bài' });
    await client.post(`/api/articles/${made.articleId}/approve`, undefined, await sm());
    const slug = made.slug!;
    expect((await client.get(`/api/public/articles/${slug}`)).status).toBe(200);
    await client.post(`/api/articles/${made.articleId}/hide`, undefined, await sm());
    expect((await failure(client.get(`/api/public/articles/${slug}`))).status).toBe(404);
    expect(article(await client.get(`/api/me/articles/${made.articleId}`, author)).status).toBe('HIDDEN');
    await client.post(`/api/articles/${made.articleId}/publish`, undefined, await sm());
    expect((await client.get(`/api/public/articles/${slug}`)).status).toBe(200);
  });
});

describe('từ chối (#8)', () => {
  it('PENDING_REVIEW → REJECTED kèm lý do; bác sĩ thấy lý do và thông báo; không hiện công khai', async () => {
    const res = await client.post('/api/articles/15/reject', { reviewNote: '  Cần dẫn nguồn tham khảo.  ' }, await sm());
    expect(article(res)).toMatchObject({ articleId: 15, status: 'REJECTED', reviewNote: 'Cần dẫn nguồn tham khảo.' });
    expect(article(await client.get('/api/me/articles/15', await vet2()))).toMatchObject({ status: 'REJECTED', reviewNote: 'Cần dẫn nguồn tham khảo.' });
    expect(await notificationTypes(await vet2())).toContain('ARTICLE_REVIEW_REJECTED');
    expect((await adminList({ status: 'REJECTED' })).content.map((a) => a.articleId).sort()).toEqual([13, 15]);
  });

  it('chu trình: từ chối → bác sĩ sửa (về DRAFT) → gửi lại → duyệt', async () => {
    const author = await vet();
    await client.post('/api/articles/12/reject', { reviewNote: 'Thiếu phần cảnh báo.' }, await sm());
    expect(article(await client.patch('/api/me/articles/12', { content: '<p>Đã bổ sung cảnh báo.</p>' }, author))).toMatchObject({ status: 'DRAFT', reviewNote: null });
    expect(article(await client.post('/api/me/articles/12/submit', undefined, author)).status).toBe('PENDING_REVIEW');
    expect(article(await client.post('/api/articles/12/approve', undefined, await sm())).status).toBe('PUBLISHED');
  });

  it('thứ tự lỗi: sai kiểu → 403 → VALIDATION_FAILED (quá 300) → 404 → BR-BV-06 (thiếu lý do) → 409', async () => {
    const auth = await sm();
    const reject = (id: number | string, body: object, a: object = auth) => client.post(`/api/articles/${id}/reject`, body, a);
    expect((await failure(reject(12, { reviewNote: 5 }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(reject(12, {}, await vet()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(reject(12, { reviewNote: 'x'.repeat(301) }))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(reject(999, {}))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    for (const body of [{}, { reviewNote: '   ' }, { reviewNote: null }]) {
      const res = await failure(reject(12, body));
      expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
      expect(res.data.message).toMatch(/\(BR-BV-06\)$/);
    }
    expect((await failure(reject(14, { reviewNote: 'Lý do' }))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await reject(12, { reviewNote: 'x'.repeat(300) })).status).toBe(200); // đúng 300 ký tự là hợp lệ
  });
});

describe('bài của bác sĩ trong content-v1 của SUPER_MANAGER', () => {
  it('xuất bản chỉ mở lại bài đang ẩn; ẩn bài đang đăng; không sửa / xóa bài của bác sĩ (BR-BV-05)', async () => {
    const auth = await sm();
    await adminList({ status: 'PENDING_REVIEW' }); // bật bài mẫu
    expect((await failure(client.post('/api/articles/12/publish', undefined, auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION'); // chờ duyệt: dùng approve
    expect((await failure(client.post('/api/articles/13/publish', undefined, auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION'); // bị từ chối
    expect((await failure(client.post('/api/articles/11/publish', undefined, auth))).status).toBe(404); // nháp của bác sĩ
    expect((await failure(client.post('/api/articles/12/hide', undefined, auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect(((await client.post('/api/articles/14/hide', undefined, auth)).data.data as { status: string }).status).toBe('HIDDEN');
    expect(((await client.post('/api/articles/14/publish', undefined, auth)).data.data as { status: string }).status).toBe('PUBLISHED');
    for (const call of [client.patch('/api/articles/14', { title: 'Sửa hộ' }, auth), client.delete('/api/articles/13', auth)]) {
      const res = await failure(call);
      expect(res.data.message).toMatch(/\(BR-BV-05\)$/);
    }
    expect((await failure(client.delete('/api/articles/11', auth))).status).toBe(404);
  });

  it('bài mẫu đã đăng (14) và bài đã ẩn (16) khớp trang công khai', async () => {
    await mineList(await vet());
    expect((await client.get(`/api/public/articles/${SLUG_14}`)).data.data).toMatchObject({ articleId: 14, authorDisplayName: 'BS. Minh Anh' });
    expect((await failure(client.get('/api/public/articles/meo-xu-ly-khi-cho-bi-say-xe'))).status).toBe(404);
    expect((await client.get('/api/public/articles', { params: { size: 100 } })).data.data.totalElements).toBe(9);
  });
});

// ─── Bình luận (#9–14) ───

describe('bình luận của một bài (#9)', () => {
  it('chỉ bình luận ACTIVE, cũ nhất trước, tên rút gọn, có editedAt khi đã sửa', async () => {
    const page = await publicComments(SLUG_1);
    expect(page).toMatchObject({ page: 0, size: 20, totalElements: 2, totalPages: 1 });
    expect(page.content.map((c) => c.commentId)).toEqual([1, 2]); // #1 tạo 5 ngày trước, #2 tạo 4,5 ngày trước
    expect(page.content.map((c) => c.authorName)).toEqual(['Nguyễn T.', 'Lê H.']);
    expect(page.content[1].editedAt).toEqual(expect.any(String));
    expect(page.content[0].editedAt ?? null).toBeNull();
    expect(page.content[0]).not.toHaveProperty('customerId');
    expect(page.content[0]).not.toHaveProperty('status');
    const times = page.content.map((c) => Date.parse(c.createdAt));
    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect((await publicComments(SLUG_2)).content.map((c) => c.commentId)).toEqual([4]);
  });

  it('phân trang; sai kiểu → 400; bài chưa PUBLISHED hoặc không có → 404', async () => {
    expect(await publicComments(SLUG_1, { size: 1 })).toMatchObject({ size: 1, totalElements: 2, totalPages: 2 });
    expect((await publicComments(SLUG_1, { page: 1, size: 1 })).content.map((c) => c.commentId)).toEqual([2]);
    expect(await publicComments(SLUG_1, { page: 9 })).toMatchObject({ content: [], totalElements: 2 });
    expect((await failure(client.get(`/api/public/articles/${SLUG_1}/comments`, { params: { size: 'x' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/public/articles/khong-co-bai-nay/comments'))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404 });
    const hidden = (await adminList({ status: 'HIDDEN' })).content[0];
    const draft = (await adminList({ status: 'DRAFT' })).content[0];
    for (const a of [hidden, draft]) {
      const slug = (await client.get(`/api/articles/${a.articleId}`, await sm())).data.data.slug as string;
      expect((await failure(client.get(`/api/public/articles/${slug}/comments`))).status).toBe(404);
    }
  });

  it('bài bị ẩn (content-v1) thì bình luận không hiện nữa; mở lại thì hiện lại', async () => {
    await mineList(await vet());
    expect((await publicComments(SLUG_14)).content.map((c) => c.commentId)).toEqual([6]);
    await client.post('/api/articles/14/hide', undefined, await sm());
    expect((await failure(client.get(`/api/public/articles/${SLUG_14}/comments`))).status).toBe(404);
    await client.post('/api/articles/14/publish', undefined, await sm());
    expect((await publicComments(SLUG_14)).totalElements).toBe(1);
  });
});

describe('đăng bình luận (#10)', () => {
  const post = async (articleId: number, content: unknown, auth?: object) => client.post(`/api/me/articles/${articleId}/comments`, { content }, auth ?? (await customer()));

  it('201 ACTIVE, hiện cuối danh sách công khai với tên rút gọn; cắt khoảng trắng', async () => {
    const res = await post(1, '  Bài viết rất hữu ích!  ');
    expect(res.status).toBe(201);
    expect(comment(res)).toMatchObject({ articleId: 1, content: 'Bài viết rất hữu ích!', status: 'ACTIVE', editedAt: null, hideReason: null });
    const page = await publicComments(SLUG_1);
    expect(page.content.at(-1)).toMatchObject({ commentId: comment(res).commentId, authorName: 'Nguyễn T.', content: 'Bài viết rất hữu ích!' });
    // Khách khác tên khác.
    const other = await post(1, 'Mình đồng ý', await customer2());
    expect((await publicComments(SLUG_1)).content.at(-1)).toMatchObject({ commentId: comment(other).commentId, authorName: 'Lê L.' });
  });

  it('BR-BV-08: rỗng / chỉ khoảng trắng / quá 1 000 ký tự; đúng 1 000 thì được; emoji đếm một ký tự', async () => {
    for (const content of ['', '   ', 'x'.repeat(1001)]) {
      const res = await failure(post(1, content));
      expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
      expect(res.data.message).toMatch(/\(BR-BV-08\)$/);
    }
    expect((await failure(client.post('/api/me/articles/1/comments', {}, await customer()))).data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expect((await post(1, 'x'.repeat(1000))).status).toBe(201);
    expect((await post(1, '🐶'.repeat(1000))).status).toBe(201);
  });

  it('thứ tự lỗi: sai kiểu → 403 → 404 → BR-BV-08 → 429', async () => {
    expect((await failure(client.post('/api/me/articles/abc/comments', { content: 'x' }, await customer()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/me/articles/1/comments', { content: 5 }, await vet()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/me/articles/1/comments', { content: 'x' }, await vet()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(post(999, ''))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    expect((await failure(post(6, 'x'))).status).toBe(404); // bài nháp
  });

  it('chỉ bài PUBLISHED: bài bị ẩn / chờ duyệt / bị từ chối là 404', async () => {
    await mineList(await vet());
    for (const id of [11, 12, 13, 15, 16]) expect((await failure(post(id, 'Bình luận'))).status).toBe(404);
    expect((await post(14, 'Bài của bác sĩ đã đăng')).status).toBe(201);
    await client.post('/api/articles/14/hide', undefined, await sm());
    expect((await failure(post(14, 'Sau khi ẩn'))).status).toBe(404);
  });

  it('429 sau 10 bình luận trong một giờ (kể cả đã xóa); khách khác không bị ảnh hưởng; qua một giờ thì đăng lại được', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-06T10:00:00+07:00'));
    const auth = await customer();
    for (let i = 0; i < 10; i++) {
      const res = await post(1, `Bình luận ${i}`, auth);
      expect(res.status).toBe(201);
      if (i === 0) await client.delete(`/api/me/comments/${comment(res).commentId}`, auth); // xóa vẫn tính vào giới hạn
      vi.setSystemTime(new Date('2026-11-06T10:00:00+07:00').getTime() + (i + 1) * 60_000);
    }
    const blocked = await failure(post(1, 'Bình luận thứ 11', auth));
    expect(blocked.data).toMatchObject({ errorCode: 'TOO_MANY_REQUESTS', statusCode: 429 });
    expect((await post(1, 'Khách khác vẫn đăng được', await customer2())).status).toBe(201);
    expect((await client.patch('/api/me/comments/1', { content: 'Sửa khi đang bị giới hạn' }, auth)).status).toBe(200); // sửa không tính vào giới hạn

    vi.setSystemTime(new Date('2026-11-06T10:00:00+07:00').getTime() + 61 * 60_000);
    expect((await post(1, 'Sau một giờ', auth)).status).toBe(201); // bình luận đầu đã quá 60 phút
    expect((await failure(post(1, 'Lại quá giới hạn', auth))).status).toBe(429); // 9 bình luận còn trong cửa sổ + bình luận vừa đăng
  });
});

describe('sửa / xóa bình luận của tôi (#11, #12)', () => {
  const edit = (id: number, content: unknown, auth: object) => client.patch(`/api/me/comments/${id}`, { content }, auth);

  it('sửa bình luận của mình: đổi nội dung, có editedAt, hiện ở trang công khai', async () => {
    const auth = await customer();
    const res = await edit(1, '  Đã sửa lại cho rõ ý.  ', auth);
    expect(comment(res)).toMatchObject({ commentId: 1, content: 'Đã sửa lại cho rõ ý.', status: 'ACTIVE', editedAt: expect.any(String) });
    expect((await publicComments(SLUG_1)).content.find((c) => c.commentId === 1)).toMatchObject({ content: 'Đã sửa lại cho rõ ý.', editedAt: expect.any(String) });
  });

  it('thứ tự lỗi: sai kiểu → 403 → 404 → 403 phạm vi (của người khác) → BR-BV-08 → 409', async () => {
    const auth = await customer();
    expect((await failure(edit(1, 5, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.patch('/api/me/comments/abc', { content: 'x' }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(edit(1, 'x', await vet()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(edit(999, '', auth))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    const foreign = await failure(edit(2, 'Sửa hộ người khác', auth)); // #2 của hồ sơ quầy 4
    expect(foreign.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', statusCode: 403 });
    expect((await failure(edit(2, '', auth))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH'); // phạm vi trước nội dung
    expect((await failure(edit(1, '', auth))).data.message).toMatch(/\(BR-BV-08\)$/);
    expect((await failure(edit(1, 'x'.repeat(1001), auth))).data.message).toMatch(/\(BR-BV-08\)$/);
    expect((await failure(edit(5, 'Sửa bình luận đã xóa', auth))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 }); // #5 đã xóa
  });

  it('bình luận bị ẩn thì chủ không sửa được (409); bỏ ẩn thì sửa lại được', async () => {
    const auth = await customer();
    const made = comment(await client.post('/api/me/articles/1/comments', { content: 'Bình luận sẽ bị ẩn' }, auth));
    await client.post(`/api/comments/${made.commentId}/hide`, { reason: 'Vi phạm' }, await sm());
    expect((await failure(edit(made.commentId, 'Cố sửa', auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await client.post(`/api/comments/${made.commentId}/unhide`, undefined, await sm());
    expect(comment(await edit(made.commentId, 'Sửa lại', auth)).content).toBe('Sửa lại');
  });

  it('xóa mềm: 204, biến mất khỏi trang công khai; gọi lại vẫn 204 (idempotent)', async () => {
    const auth = await customer();
    const res = await client.delete('/api/me/comments/1', auth);
    expect(res.status).toBe(204);
    expect((await publicComments(SLUG_1)).content.map((c) => c.commentId)).toEqual([2]);
    expect((await client.delete('/api/me/comments/1', auth)).status).toBe(204);
    expect((await client.delete('/api/me/comments/5', auth)).status).toBe(204); // #5 vốn đã xóa
    expect((await failure(edit(1, 'Sửa sau khi xóa', auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('xóa: bình luận của người khác 403, id lạ 404, sai vai trò 403, id sai kiểu 400', async () => {
    const auth = await customer();
    expect((await failure(client.delete('/api/me/comments/2', auth))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.delete('/api/me/comments/999', auth))).status).toBe(404);
    expect((await failure(client.delete('/api/me/comments/1', await sm()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.delete('/api/me/comments/x', auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await publicComments(SLUG_1)).totalElements).toBe(2); // chưa mất gì
  });

  it('chủ xóa bình luận đang bị ẩn: thành DELETED, không bỏ ẩn được nữa (409)', async () => {
    const auth = await customer();
    const made = comment(await client.post('/api/me/articles/1/comments', { content: 'Sẽ bị ẩn rồi xóa' }, auth));
    await client.post(`/api/comments/${made.commentId}/hide`, { reason: 'Vi phạm' }, await sm());
    expect((await client.delete(`/api/me/comments/${made.commentId}`, auth)).status).toBe(204);
    expect((await failure(client.post(`/api/comments/${made.commentId}/unhide`, undefined, await sm()))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

describe('ẩn / bỏ ẩn bình luận (#13, #14)', () => {
  const hide = (id: number | string, body: object, auth: object) => client.post(`/api/comments/${id}/hide`, body, auth);

  it('ẩn kèm lý do rồi bỏ ẩn: trạng thái, lý do, hiển thị công khai', async () => {
    const auth = await sm();
    const hidden = comment(await hide(1, { reason: '  Có ngôn từ phản cảm  ' }, auth));
    expect(hidden).toMatchObject({ commentId: 1, status: 'HIDDEN', hideReason: 'Có ngôn từ phản cảm' });
    expect((await publicComments(SLUG_1)).content.map((c) => c.commentId)).toEqual([2]);
    const shown = comment(await client.post('/api/comments/1/unhide', undefined, auth));
    expect(shown).toMatchObject({ commentId: 1, status: 'ACTIVE', hideReason: null });
    expect((await publicComments(SLUG_1)).content.map((c) => c.commentId)).toEqual([1, 2]);
  });

  it('bình luận ẩn mẫu (#3) bỏ ẩn được và hiện công khai (không có endpoint liệt kê bình luận ẩn, A6)', async () => {
    expect(comment(await client.post('/api/comments/3/unhide', undefined, await sm()))).toMatchObject({ status: 'ACTIVE', hideReason: null });
    expect((await publicComments(SLUG_1)).totalElements).toBe(3);
  });

  it('thứ tự lỗi: sai kiểu → 403 → VALIDATION_FAILED (quá 300) → 404 → BR-BV-08 (thiếu lý do) → 409', async () => {
    const auth = await sm();
    expect((await failure(hide('abc', { reason: 'x' }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(hide(1, { reason: 5 }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(hide(1, {}, await customer()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(hide(1, { reason: 'x'.repeat(301) }, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(hide(999, {}, auth))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    for (const body of [{}, { reason: '  ' }]) {
      const res = await failure(hide(1, body, auth));
      expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
      expect(res.data.message).toMatch(/\(BR-BV-08\)$/);
    }
    expect(comment(await hide(1, { reason: 'x'.repeat(300) }, auth)).status).toBe('HIDDEN');
  });

  it('409: ẩn bình luận không ACTIVE (đã ẩn, đã xóa), bỏ ẩn bình luận không HIDDEN', async () => {
    const auth = await sm();
    for (const id of [3, 5]) expect((await failure(hide(id, { reason: 'Lý do' }, auth))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    for (const id of [1, 5]) expect((await failure(client.post(`/api/comments/${id}/unhide`, undefined, auth))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/comments/999/unhide', undefined, auth))).status).toBe(404);
    expect((await failure(client.post('/api/comments/abc/unhide', undefined, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

// ─── State cũ ───

describe('state cũ trong localStorage', () => {
  it('state bình luận chỉ có {} hoặc thiếu bảng vẫn chạy cả #9–14, id mới không trùng id mẫu', async () => {
    localStorage.setItem('petcare-mock-articlex', '{}');
    expect((await publicComments(SLUG_1)).totalElements).toBe(2);
    const made = comment(await client.post('/api/me/articles/1/comments', { content: 'Sau state cũ' }, await customer()));
    expect(made.commentId).toBeGreaterThan(6);
    localStorage.setItem('petcare-mock-articlex', JSON.stringify({ comments: [{ commentId: 40, articleId: 1, customerId: 1, content: 'Cũ', status: 'ACTIVE', createdAt: '2026-10-01T00:00:00+07:00' }] }));
    expect((await publicComments(SLUG_1)).content.map((c) => c.commentId)).toEqual([40]);
    expect(comment(await client.post('/api/me/articles/1/comments', { content: 'Mới' }, await customer())).commentId).toBe(41);
    expect(comment(await client.post('/api/comments/40/hide', { reason: 'Thử' }, await sm())).status).toBe('HIDDEN'); // dòng cũ thiếu cột hide* không làm nổ
  });

  it('state bài viết cũ (chưa có cột của bác sĩ, hoặc chỉ {}) vẫn dùng được; bài của SUPER_MANAGER không có tác giả bác sĩ', async () => {
    localStorage.setItem('petcare-mock-content', '{}');
    expect((await mineList(await vet())).content.length).toBeGreaterThanOrEqual(4);
    expect((await client.get('/api/public/articles')).data.data.totalElements).toBeGreaterThanOrEqual(9);
    expect((await failure(client.get('/api/me/articles/1', await vet()))).status).toBe(404);
    localStorage.removeItem('petcare-mock-content');
    expect((await failure(client.post('/api/articles/1/approve', undefined, await sm()))).data.errorCode).toBe('INVALID_STATE_TRANSITION'); // PUBLISHED, của SUPER_MANAGER
  });

  it('bài mẫu của bác sĩ chỉ tạo một lần dù gọi nhiều route', async () => {
    await mineList(await vet());
    await mineList(await vet2());
    await adminList({ status: 'PENDING_REVIEW' });
    const ids = (await adminList({ size: 100 })).content.map((a) => a.articleId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.filter((id) => id >= 11 && id <= 16).sort()).toEqual([12, 13, 14, 15, 16]);
  });
});
