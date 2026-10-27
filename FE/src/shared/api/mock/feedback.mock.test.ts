import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import { findCustomer } from './customer.mock';
import { FEEDBACK_STATE_KEY, type FeedbackRecord } from './feedback.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (feedback.mock.ts), đồng hồ ghim ở thứ Ba 27/10/2026 10:00 giờ Việt Nam. Mọi feedback mẫu tạo trước 00:00 hôm nay (giờ VN):
// #1 khách 1, chi nhánh 1, RESOLVED (bởi #3) · #2 khách 1, WEBSITE không chi nhánh, SEEN · #3 khách 1, chi nhánh 1, NEW ·
// #4 khách 3, chi nhánh 1, SEEN · #5 khách 4, OTHER không chi nhánh, NEW · #6 khách 4, chi nhánh 1, RESOLVED (bởi #2).
// createdAt giảm dần: #3, #5, #4, #2, #6, #1. Chi nhánh ACTIVE duy nhất: 1 (2 và 3 là DRAFT). Khách 7 = khach@, 10 = khachmoi@ (khách 2, chờ liên kết).
const TUE_10 = '2026-10-27T10:00:00+07:00';
const setNow = (iso: string) => vi.setSystemTime(new Date(iso));

let client: AxiosInstance;

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  setNow(TUE_10);
  client = axios.create();
  installMockApi(client);
});

afterEach(() => vi.useRealTimers());

type Who = { headers: { Authorization: string } };
async function as(email: string): Promise<Who> {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const customer = () => as('khach@petcare.vn');
const pending = () => as('khachmoi@petcare.vn');
const manager = () => as('manager@store1.vn');
const manager2 = () => as('manager2@store2.vn');
const chain = () => as('chuoi@petcare.vn');
const desk = () => as('reception@store1.vn');
const admin = () => as('admin@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}
function expectFail(res: AxiosResponse, status: number, errorCode: string, message?: string) {
  expect(res.status).toBe(status);
  expect(res.data).toMatchObject({ success: false, errorCode, statusCode: status });
  if (message !== undefined) expect(res.data.message).toBe(message);
}
const ruleOf = (res: AxiosResponse) => ruleIdOf(res.data.message);

const BODY = { topic: 'WEBSITE', content: 'Trang web dễ dùng, đặt lịch nhanh.' };
const submit = (who: Who, body: unknown = BODY) => client.post('/api/me/feedbacks', body, who);
const mine = async (who: Who) => (await client.get('/api/me/feedbacks', who)).data.data as { feedbackId: number; [k: string]: unknown }[];
const listOf = (who: Who, params: Record<string, unknown> = {}) => client.get('/api/feedbacks', { params, ...who });
const idsOf = (res: AxiosResponse) => (res.data.data.content as { feedbackId: number }[]).map((f) => f.feedbackId);
const open = (who: Who, id: number | string) => client.get(`/api/feedbacks/${id}`, who);
const resolve = (who: Who, id: number | string, body: unknown = { resolutionNote: 'Đã gọi lại khách xin lỗi.' }) =>
  client.post(`/api/feedbacks/${id}/resolve`, body, who);

/** Bản ghi đầy đủ cho test ghi thẳng state (bỏ dữ liệu mẫu). */
const rec = (r: Partial<FeedbackRecord> & { feedbackId: number }): FeedbackRecord => ({
  customerId: 1,
  topic: 'WEBSITE',
  branchId: null,
  rating: null,
  content: 'Góp ý đủ dài để hợp lệ.',
  status: 'NEW',
  seenAt: null,
  resolvedBy: null,
  resolvedAt: null,
  resolutionNote: null,
  createdAt: '2026-10-20T03:00:00.000Z',
  ...r,
});
const inject = (records: FeedbackRecord[]) => localStorage.setItem(FEEDBACK_STATE_KEY, JSON.stringify({ feedbacks: records }));
const stored = () => JSON.parse(localStorage.getItem(FEEDBACK_STATE_KEY)!) as { feedbacks: FeedbackRecord[] };

const MY_KEYS = ['branchId', 'content', 'createdAt', 'feedbackId', 'rating', 'status', 'topic'];
const FULL_KEYS = [
  'branchId', 'branchName', 'content', 'createdAt', 'customerId', 'customerName', 'feedbackId', 'rating', 'resolutionNote', 'resolvedAt',
  'resolvedBy', 'seenAt', 'status', 'topic',
];

describe('dữ liệu mẫu', () => {
  it('6 feedback đủ NEW / SEEN / RESOLVED, có WEBSITE và OTHER không gắn chi nhánh, tạo trước 00:00 hôm nay giờ VN', async () => {
    const res = await listOf(await chain());
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 6, totalPages: 1 });
    expect(idsOf(res)).toEqual([3, 5, 4, 2, 6, 1]);
    const all = res.data.data.content as { feedbackId: number; status: string; topic: string; branchId: number | null; customerId: number; createdAt: string }[];
    const byId = (id: number) => all.find((f) => f.feedbackId === id)!;
    expect(all.filter((f) => f.status === 'NEW').map((f) => f.feedbackId)).toEqual([3, 5]);
    expect(all.filter((f) => f.status === 'SEEN').map((f) => f.feedbackId)).toEqual([4, 2]);
    expect(all.filter((f) => f.status === 'RESOLVED').map((f) => f.feedbackId)).toEqual([6, 1]);
    expect(byId(2)).toMatchObject({ topic: 'WEBSITE', branchId: null });
    expect(byId(5)).toMatchObject({ topic: 'OTHER', branchId: null });
    // Khách 2 (khachmoi@) đang chờ quyết định liên kết nên không thể có feedback; chỉ khách 1, 3, 4.
    expect(new Set(all.map((f) => f.customerId))).toEqual(new Set([1, 3, 4]));
    const midnight = Date.parse('2026-10-27T00:00:00+07:00');
    for (const f of all) expect(Date.parse(f.createdAt)).toBeLessThan(midnight);
  });

  it('thời điểm mẫu ổn định giữa các request trong ngày (không dịch theo đồng hồ), nên không đếm vào giới hạn hôm nay', async () => {
    const before = (await listOf(await chain())).data.data.content.map((f: { createdAt: string }) => f.createdAt);
    setNow('2026-10-27T13:00:00+07:00');
    const after = (await listOf(await chain())).data.data.content.map((f: { createdAt: string }) => f.createdAt);
    expect(after).toEqual(before);
  });

  it('feedback RESOLVED mẫu có người xử lý, lúc xử lý và ghi chú nội bộ; SEEN có seenAt', async () => {
    const resolved = (await open(await chain(), 1)).data.data;
    expect(resolved).toMatchObject({ status: 'RESOLVED', resolvedBy: 3 });
    expect(resolved.resolutionNote).toEqual(expect.stringContaining('ghi chú nội bộ'));
    expect(Date.parse(resolved.resolvedAt)).toBeGreaterThan(Date.parse(resolved.seenAt));
    expect((await open(await chain(), 2)).data.data.seenAt).toEqual(expect.any(String));
  });
});

describe('POST /api/me/feedbacks (#18, UC83; BR-DG-01, BR-TK-19)', () => {
  it('201, trả MyFeedback (đúng 7 trường) trạng thái NEW, createdAt là bây giờ', async () => {
    const res = await submit(await customer(), { ...BODY, rating: 5 });
    expect(res.status).toBe(201);
    expect(Object.keys(res.data.data).sort()).toEqual(MY_KEYS);
    expect(res.data.data).toMatchObject({
      feedbackId: 7,
      topic: 'WEBSITE',
      branchId: null,
      rating: 5,
      content: BODY.content,
      status: 'NEW',
      createdAt: '2026-10-27T03:00:00.000Z',
    });
  });

  it('chuẩn hóa NFC rồi strip trước khi lưu', async () => {
    const decomposed = '  Dịch vụ rất tốt, cảm ơn phòng khám.  '.normalize('NFD');
    expect(decomposed).not.toBe(decomposed.normalize('NFC'));
    const res = await submit(await customer(), { ...BODY, content: decomposed });
    expect(res.data.data.content).toBe('Dịch vụ rất tốt, cảm ơn phòng khám.');
    expect(stored().feedbacks.slice(-1)[0].content).toBe('Dịch vụ rất tốt, cảm ơn phòng khám.');
  });

  describe('độ dài nội dung: 10–2000 code point sau NFC + strip', () => {
    it('9 emoji bị từ chối, 10 emoji được nhận (emoji là 1 code point, không phải 2)', async () => {
      const who = await customer();
      const nine = await failure(submit(who, { ...BODY, content: '😀'.repeat(9) }));
      expectFail(nine, 400, 'BUSINESS_RULE_VIOLATION', 'Nội dung feedback phải từ 10 đến 2000 ký tự (BR-DG-01)');
      expect(ruleOf(nine)).toBe('BR-DG-01');
      expect((await submit(who, { ...BODY, content: '😀'.repeat(10) })).status).toBe(201);
    });

    it('khoảng trắng hai đầu không được tính', async () => {
      expect(ruleOf(await failure(submit(await customer(), { ...BODY, content: '   ngắn   ' })))).toBe('BR-DG-01');
      expect(ruleOf(await failure(submit(await customer(), { ...BODY, content: '          ' })))).toBe('BR-DG-01');
    });

    it('2001 ký tự bị từ chối, 2000 được nhận; 1001 emoji (2002 đơn vị UTF-16 nhưng 1001 code point) được nhận', async () => {
      const who = await customer();
      expect(ruleOf(await failure(submit(who, { ...BODY, content: 'a'.repeat(2001) })))).toBe('BR-DG-01');
      expect((await submit(who, { ...BODY, content: 'a'.repeat(2000) })).status).toBe(201);
      expect((await submit(who, { ...BODY, content: '😀'.repeat(1001) })).status).toBe(201);
    });
  });

  describe('điểm 1–5 tùy chọn', () => {
    it.each([0, 6, -1])('điểm %i bị từ chối', async (rating) => {
      const res = await failure(submit(await customer(), { ...BODY, rating }));
      expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', 'Mức hài lòng phải từ 1 đến 5 (BR-DG-01)');
    });

    it('điểm 1, 5, vắng hoặc null đều được nhận', async () => {
      const who = await customer();
      expect((await submit(who, { ...BODY, rating: 1 })).data.data.rating).toBe(1);
      expect((await submit(who, { ...BODY, rating: 5 })).data.data.rating).toBe(5);
      expect((await submit(who, BODY)).data.data.rating).toBeNull();
      expect((await submit(who, { ...BODY, rating: null })).data.data.rating).toBeNull();
    });
  });

  describe('chi nhánh', () => {
    it('BRANCH_SERVICE thiếu chi nhánh: 400 BR-DG-01', async () => {
      const res = await failure(submit(await customer(), { topic: 'BRANCH_SERVICE', content: BODY.content }));
      expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', 'Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh (BR-DG-01)');
    });

    it('BRANCH_SERVICE với chi nhánh ACTIVE được nhận; WEBSITE kèm chi nhánh ACTIVE cũng lưu chi nhánh đó', async () => {
      const who = await customer();
      expect((await submit(who, { topic: 'BRANCH_SERVICE', branchId: 1, content: BODY.content })).data.data.branchId).toBe(1);
      expect((await submit(who, { ...BODY, branchId: 1 })).data.data.branchId).toBe(1);
      expect((await submit(who, { topic: 'OTHER', content: BODY.content })).data.data.branchId).toBeNull();
    });

    it.each([999999, 2, 3])('chi nhánh %i không có hoặc không ACTIVE (DRAFT): 404, với mọi chủ đề có gửi branchId', async (branchId) => {
      const who = await customer();
      for (const topic of ['BRANCH_SERVICE', 'WEBSITE', 'OTHER']) {
        const res = await failure(submit(who, { topic, branchId, content: BODY.content }));
        expectFail(res, 404, 'RESOURCE_NOT_FOUND', `Không tìm thấy chi nhánh #${branchId}`);
      }
      expect(await mine(who)).toHaveLength(3);
    });
  });

  describe('lỗi dữ liệu vào (400 VALIDATION_FAILED / MALFORMED_REQUEST, đến trước mọi luật)', () => {
    it('thiếu topic hoặc content: câu báo theo từng trường', async () => {
      const who = await customer();
      expectFail(await failure(submit(who, { content: BODY.content })), 400, 'VALIDATION_FAILED', 'topic: Vui lòng chọn chủ đề');
      expectFail(await failure(submit(who, { topic: 'WEBSITE' })), 400, 'VALIDATION_FAILED', 'content: Vui lòng nhập nội dung');
      expectFail(await failure(submit(who, {})), 400, 'VALIDATION_FAILED', 'topic: Vui lòng chọn chủ đề; content: Vui lòng nhập nội dung');
    });

    it.each([0, -1])('branchId %i trong body: câu "Mã chi nhánh phải là số dương" (khác câu của query)', async (branchId) => {
      const res = await failure(submit(await customer(), { ...BODY, branchId }));
      expectFail(res, 400, 'VALIDATION_FAILED', 'branchId: Mã chi nhánh phải là số dương');
    });

    it.each([
      ['topic lạ', { topic: 'LA', content: BODY.content }],
      ['content không phải chuỗi', { topic: 'WEBSITE', content: 12345 }],
      ['branchId không phải số', { ...BODY, branchId: 'một' }],
      ['rating không phải số', { ...BODY, rating: 'năm' }],
      ['body là mảng', [BODY]],
    ])('%s: 400 MALFORMED_REQUEST', async (_name, body) => {
      const res = await failure(submit(await customer(), body));
      expectFail(res, 400, 'MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu');
    });
  });

  describe('thứ tự kiểm: body 400 → vai trò 403 → hồ sơ (BR-TK-19) → nội dung → điểm → thiếu chi nhánh → chi nhánh 404 → giới hạn ngày', () => {
    it('401 khi không có token', async () => {
      expectFail(await failure(client.post('/api/me/feedbacks', BODY)), 401, 'UNAUTHENTICATED');
    });

    it('body sai đến trước vai trò: lễ tân gửi {} nhận 400; gửi body đúng nhận 403', async () => {
      const who = await desk();
      expectFail(await failure(submit(who, {})), 400, 'VALIDATION_FAILED');
      expectFail(await failure(submit(who, BODY)), 403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này');
    });

    it('chỉ CUSTOMER gửi được: nhân viên mọi vai trò 403, kể cả nội dung ngắn (vai trò đến trước luật)', async () => {
      for (const who of [await desk(), await manager(), await chain(), await admin()]) {
        expectFail(await failure(submit(who, { ...BODY, content: 'ngắn' })), 403, 'ACCESS_DENIED');
      }
    });

    it('khách chờ quyết định liên kết: 400 BR-TK-19, không ghi gì, và đến trước cả lỗi nội dung', async () => {
      const who = await pending();
      const res = await failure(submit(who, BODY));
      expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', 'Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám (BR-TK-19)');
      expect(ruleOf(res)).toBe('BR-TK-19');
      expect(ruleOf(await failure(submit(who, { ...BODY, content: 'ngắn' })))).toBe('BR-TK-19');
      expect(await mine(who)).toEqual([]);
      expect(findCustomer(2)!.hasData).toBe(false);
    });

    it('nội dung → điểm → thiếu chi nhánh → chi nhánh không ACTIVE: mỗi lần sửa một lỗi thì lộ lỗi kế tiếp', async () => {
      const who = await customer();
      const body = { topic: 'BRANCH_SERVICE', content: 'ngắn', rating: 9 } as Record<string, unknown>;
      expect((await failure(submit(who, body))).data.message).toBe('Nội dung feedback phải từ 10 đến 2000 ký tự (BR-DG-01)');
      body.content = BODY.content;
      expect((await failure(submit(who, body))).data.message).toBe('Mức hài lòng phải từ 1 đến 5 (BR-DG-01)');
      body.rating = 4;
      expect((await failure(submit(who, body))).data.message).toBe('Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh (BR-DG-01)');
      body.branchId = 2;
      expectFail(await failure(submit(who, body)), 404, 'RESOURCE_NOT_FOUND');
      body.branchId = 1;
      expect((await submit(who, body)).status).toBe(201);
    });
  });

  describe('giới hạn 5 feedback mỗi khách mỗi ngày theo giờ Việt Nam (BR-DG-01 [CFG] feedback.max_per_day)', () => {
    async function sendFive(who: Who) {
      for (let i = 0; i < 5; i++) expect((await submit(who, { ...BODY, content: `Góp ý số ${i} của khách hàng.` })).status).toBe(201);
    }

    it('feedback thứ 6 trong ngày bị từ chối kèm thời điểm gửi tiếp; 5 feedback mẫu từ hôm trước không tính', async () => {
      const who = await customer();
      await sendFive(who);
      const sixth = await failure(submit(who, BODY));
      expectFail(
        sixth,
        400,
        'BUSINESS_RULE_VIOLATION',
        'Bạn đã gửi đủ 5 feedback hôm nay, có thể gửi tiếp từ 00:00 ngày 28/10/2026 (BR-DG-01)',
      );
      expect(ruleOf(sixth)).toBe('BR-DG-01');
      expect((await mine(who)).filter((f) => Date.parse(f.createdAt as string) >= Date.parse('2026-10-27T00:00:00+07:00'))).toHaveLength(5);
    });

    it('feedback của khách khác hôm nay không tính vào giới hạn của tôi', async () => {
      await sendFive(await customer());
      const other = await pending();
      await client.post('/api/me/link/decline', undefined, other);
      expect((await submit(other, BODY)).status).toBe(201);
    });

    it('lỗi nội dung đến trước lỗi giới hạn; chi nhánh không ACTIVE (404) cũng đến trước', async () => {
      const who = await customer();
      await sendFive(who);
      expect((await failure(submit(who, { ...BODY, content: 'ngắn' }))).data.message).toBe('Nội dung feedback phải từ 10 đến 2000 ký tự (BR-DG-01)');
      expectFail(await failure(submit(who, { ...BODY, branchId: 2 })), 404, 'RESOURCE_NOT_FOUND');
    });

    it('qua nửa đêm giờ Việt Nam thì gửi lại được: 23:59 (16:59Z) còn chặn, 00:00 ngày sau (17:00Z) mở', async () => {
      const who = await customer();
      setNow('2026-10-27T23:59:00+07:00');
      await sendFive(who);
      const blocked = await failure(submit(who, BODY));
      expect(blocked.data.message).toContain('có thể gửi tiếp từ 00:00 ngày 28/10/2026');

      setNow('2026-10-28T00:00:00+07:00');
      expect((await submit(who, BODY)).status).toBe(201);
    });

    it('ngày gửi tiếp qua tháng và năm: 31/12 thì ghi 01/01 năm sau', async () => {
      const who = await customer();
      setNow('2026-12-31T23:30:00+07:00');
      await sendFive(who);
      expect((await failure(submit(who, BODY))).data.message).toContain('00:00 ngày 01/01/2027');
    });
  });

  describe('sau khi gửi', () => {
    it('hồ sơ online của khách có dữ liệu nên không liên kết được với hồ sơ tại quầy nữa (BR-TK-19)', async () => {
      const who = await pending();
      await client.post('/api/me/link/decline', undefined, who);
      expect(findCustomer(2)!.hasData).toBe(false);
      await submit(who, BODY);
      expect(findCustomer(2)!.hasData).toBe(true);
    });

    it('SUPER_MANAGER thấy ngay feedback vừa gửi ở đầu danh sách, trạng thái NEW, kèm tên khách', async () => {
      await submit(await customer(), { topic: 'BRANCH_SERVICE', branchId: 1, content: BODY.content, rating: 4 });
      const res = await listOf(await chain());
      expect(idsOf(res)[0]).toBe(7);
      expect(res.data.data.content[0]).toMatchObject({ customerName: 'Nguyễn Thu Trang', branchName: 'PetCare Cầu Giấy', status: 'NEW', seenAt: null });
    });
  });
});

describe('GET /api/me/feedbacks (#19)', () => {
  it('mảng (không phân trang) của riêng tôi, mới nhất trước, chỉ 7 trường của MyFeedback', async () => {
    const res = await client.get('/api/me/feedbacks', await customer());
    expect(res.status).toBe(200);
    expect(Array.isArray(res.data.data)).toBe(true);
    expect((res.data.data as { feedbackId: number }[]).map((f) => f.feedbackId)).toEqual([3, 2, 1]);
    for (const item of res.data.data) expect(Object.keys(item).sort()).toEqual(MY_KEYS);
  });

  it('không lộ ghi chú nội bộ dù feedback đã RESOLVED, kể cả sau khi quản lý vừa xử lý', async () => {
    const who = await customer();
    const note = (await open(await chain(), 1)).data.data.resolutionNote as string;
    expect(JSON.stringify(await mine(who))).not.toContain(note);
    await open(await manager(), 3);
    await resolve(await manager(), 3, { resolutionNote: 'Ghi chú nội bộ mới, khách không được thấy.' });
    const list = await mine(who);
    expect(list.find((f) => f.feedbackId === 3)!.status).toBe('RESOLVED');
    expect(JSON.stringify(list)).not.toContain('khách không được thấy');
    expect(JSON.stringify(list)).not.toContain('resolutionNote');
  });

  it('feedback vừa gửi lên đầu; trong cùng một thời điểm ghim thì mã lớn hơn đứng trước (createdAt DESC, id DESC)', async () => {
    const who = await customer();
    await submit(who, BODY);
    await submit(who, BODY);
    await submit(who, BODY);
    expect((await mine(who)).map((f) => f.feedbackId)).toEqual([9, 8, 7, 3, 2, 1]);
  });

  it('chỉ của chính mình: khách khác không thấy feedback của khách 1', async () => {
    const other = await pending();
    await client.post('/api/me/link/decline', undefined, other);
    await submit(other, BODY);
    expect((await mine(other)).map((f) => f.feedbackId)).toEqual([7]);
    expect((await mine(await customer())).map((f) => f.feedbackId)).toEqual([3, 2, 1]);
  });

  describe('khách còn chờ quyết định liên kết (BR-TK-19): BE FeedbackSubmitService.listMine không có cổng riêng', () => {
    it('200 mảng rỗng (khách 2 không có feedback nào)', async () => {
      const res = await client.get('/api/me/feedbacks', await pending());
      expect(res.status).toBe(200);
      expect(res.data.data).toEqual([]);
    });

    it('vẫn trả feedback của chính họ nếu có (không bị cờ chặn)', async () => {
      inject([rec({ feedbackId: 1, customerId: 2, content: 'Góp ý cũ của khách chờ liên kết.' }), rec({ feedbackId: 2, customerId: 1 })]);
      const list = await mine(await pending());
      expect(list.map((f) => f.feedbackId)).toEqual([1]);
    });
  });

  it('chỉ CUSTOMER: nhân viên 403, không token 401', async () => {
    for (const who of [await desk(), await manager(), await chain(), await admin()]) {
      expectFail(await failure(client.get('/api/me/feedbacks', who)), 403, 'ACCESS_DENIED');
    }
    expectFail(await failure(client.get('/api/me/feedbacks')), 401, 'UNAUTHENTICATED');
  });
});

describe('GET /api/feedbacks (#20, UC84; BR-DG-02, 03)', () => {
  it('SUPER_MANAGER thấy tất cả, createdAt mới nhất trước, đủ 14 trường kèm tên khách và tên chi nhánh', async () => {
    const res = await listOf(await chain());
    expect(idsOf(res)).toEqual([3, 5, 4, 2, 6, 1]);
    for (const item of res.data.data.content) expect(Object.keys(item).sort()).toEqual(FULL_KEYS);
    const [first] = res.data.data.content;
    expect(first).toMatchObject({ customerId: 1, customerName: 'Nguyễn Thu Trang', branchId: 1, branchName: 'PetCare Cầu Giấy', status: 'NEW', resolutionNote: null });
    expect(res.data.data.content[1]).toMatchObject({ feedbackId: 5, branchId: null, branchName: null, rating: null });
  });

  describe('phạm vi chi nhánh', () => {
    it('BRANCH_MANAGER chi nhánh 1 chỉ thấy feedback gắn chi nhánh 1, không thấy feedback không gắn chi nhánh', async () => {
      const res = await listOf(await manager());
      expect(idsOf(res)).toEqual([3, 4, 6, 1]);
      expect(res.data.data.totalElements).toBe(4);
    });

    it('BRANCH_MANAGER chi nhánh 2 (DRAFT, không có feedback): trang rỗng, totalPages 0, không phải 403', async () => {
      const res = await listOf(await manager2());
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    });

    it('branchId của chi nhánh khác: 403 ACCESS_DENIED_SCOPE_MISMATCH; branchId của mình hoặc bỏ trống: được', async () => {
      expectFail(await failure(listOf(await manager(), { branchId: 2 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
      expectFail(await failure(listOf(await manager2(), { branchId: 1 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
      expect(idsOf(await listOf(await manager(), { branchId: 1 }))).toEqual([3, 4, 6, 1]);
    });

    it('SUPER_MANAGER lọc được theo chi nhánh bất kỳ; chi nhánh không có feedback thì trang rỗng', async () => {
      expect(idsOf(await listOf(await chain(), { branchId: 1 }))).toEqual([3, 4, 6, 1]);
      expect(idsOf(await listOf(await chain(), { branchId: 2 }))).toEqual([]);
      expect(idsOf(await listOf(await chain(), { branchId: 99 }))).toEqual([]);
    });

    it('feedback của chi nhánh khác không lọt vào danh sách của quản lý chi nhánh 1', async () => {
      inject([rec({ feedbackId: 1, topic: 'BRANCH_SERVICE', branchId: 1 }), rec({ feedbackId: 2, topic: 'BRANCH_SERVICE', branchId: 2 })]);
      expect(idsOf(await listOf(await manager()))).toEqual([1]);
      expect(idsOf(await listOf(await manager2()))).toEqual([2]);
      expect(idsOf(await listOf(await chain()))).toEqual([2, 1]);
    });
  });

  describe('bộ lọc', () => {
    it('theo trạng thái, chủ đề và kết hợp', async () => {
      const who = await chain();
      expect(idsOf(await listOf(who, { status: 'NEW' }))).toEqual([3, 5]);
      expect(idsOf(await listOf(who, { status: 'SEEN' }))).toEqual([4, 2]);
      expect(idsOf(await listOf(who, { status: 'RESOLVED' }))).toEqual([6, 1]);
      expect(idsOf(await listOf(who, { topic: 'WEBSITE' }))).toEqual([2]);
      expect(idsOf(await listOf(who, { topic: 'OTHER' }))).toEqual([5]);
      expect(idsOf(await listOf(who, { topic: 'BRANCH_SERVICE' }))).toEqual([3, 4, 6, 1]);
      expect(idsOf(await listOf(who, { topic: 'BRANCH_SERVICE', status: 'SEEN', branchId: 1 }))).toEqual([4]);
    });

    it('from / to là ngày giờ Việt Nam, gồm cả hai đầu: 23:59:59 VN còn thuộc ngày đó, 00:00:00 VN thuộc ngày sau', async () => {
      inject([
        rec({ feedbackId: 1, createdAt: '2026-10-25T17:00:00.000Z' }), // 00:00:00 ngày 26/10 VN
        rec({ feedbackId: 2, createdAt: '2026-10-26T16:59:59.000Z' }), // 23:59:59 ngày 26/10 VN
        rec({ feedbackId: 3, createdAt: '2026-10-26T17:00:00.000Z' }), // 00:00:00 ngày 27/10 VN
        rec({ feedbackId: 4, createdAt: '2026-10-25T16:59:59.000Z' }), // 23:59:59 ngày 25/10 VN
      ]);
      const who = await chain();
      expect(idsOf(await listOf(who, { from: '2026-10-26', to: '2026-10-26' }))).toEqual([2, 1]);
      expect(idsOf(await listOf(who, { from: '2026-10-27' }))).toEqual([3]);
      expect(idsOf(await listOf(who, { to: '2026-10-25' }))).toEqual([4]);
      expect(idsOf(await listOf(who, { from: '2026-10-26', to: '2026-10-27' }))).toEqual([3, 2, 1]);
    });

    it('from = to là một ngày hợp lệ; from sau to: 400 VALIDATION_FAILED', async () => {
      expect((await listOf(await chain(), { from: '2026-10-20', to: '2026-10-20' })).status).toBe(200);
      const res = await failure(listOf(await chain(), { from: '2026-10-21', to: '2026-10-20' }));
      expectFail(res, 400, 'VALIDATION_FAILED', 'Ngày bắt đầu không được sau ngày kết thúc');
    });

    it('from > to đến trước kiểm phạm vi: quản lý chi nhánh 1 hỏi chi nhánh 2 với khoảng ngày ngược nhận 400, không phải 403', async () => {
      const res = await failure(listOf(await manager(), { branchId: 2, from: '2026-10-21', to: '2026-10-20' }));
      expectFail(res, 400, 'VALIDATION_FAILED', 'Ngày bắt đầu không được sau ngày kết thúc');
      expectFail(await failure(listOf(await manager(), { branchId: 2 })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    });
  });

  describe('lỗi tham số (400, đến trước vai trò)', () => {
    it.each([
      ['branchId', 'abc'],
      ['status', 'NOPE'],
      ['topic', 'LA'],
      ['from', '2026/10/01'],
      ['from', '2026-02-30'],
      ['to', '27-10-2026'],
      ['page', 'x'],
      ['size', 'y'],
    ])('%s=%s: 400 MALFORMED_REQUEST "Tham số … sai kiểu dữ liệu"', async (name, value) => {
      const res = await failure(listOf(await chain(), { [name]: value }));
      expectFail(res, 400, 'MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`);
    });

    it.each([0, -1])('branchId=%i: 400 VALIDATION_FAILED (câu của query: "branchId: branchId phải là số dương")', async (branchId) => {
      expectFail(await failure(listOf(await chain(), { branchId })), 400, 'VALIDATION_FAILED', 'branchId: branchId phải là số dương');
    });

    it('lỗi kiểu đến trước vai trò: lễ tân gửi status=NOPE nhận 400; gửi query đúng nhận 403', async () => {
      expectFail(await failure(listOf(await desk(), { status: 'NOPE' })), 400, 'MALFORMED_REQUEST');
      expectFail(await failure(listOf(await desk())), 403, 'ACCESS_DENIED');
    });
  });

  it('chỉ SUPER_MANAGER và BRANCH_MANAGER: khách, lễ tân, ADMIN kỹ thuật 403; không token 401', async () => {
    for (const who of [await customer(), await pending(), await desk(), await admin()]) {
      expectFail(await failure(listOf(who)), 403, 'ACCESS_DENIED');
    }
    expectFail(await failure(client.get('/api/feedbacks')), 401, 'UNAUTHENTICATED');
  });

  describe('phân trang: page từ 0, size 1–100 (mặc định 20), ngoài khoảng thì kẹp', () => {
    it('chia trang giữ thứ tự mới nhất trước', async () => {
      const who = await chain();
      const first = await listOf(who, { size: 4 });
      expect(first.data.data).toMatchObject({ page: 0, size: 4, totalElements: 6, totalPages: 2 });
      expect(idsOf(first)).toEqual([3, 5, 4, 2]);
      expect(idsOf(await listOf(who, { page: 1, size: 4 }))).toEqual([6, 1]);
      expect(await listOf(who, { page: 9, size: 4 })).toMatchObject({ data: { data: { content: [], page: 9, totalElements: 6 } } });
    });

    it.each([
      [{ page: -1 }, 0, 20],
      [{ size: 0 }, 0, 1],
      [{ size: -5 }, 0, 1],
      [{ size: 101 }, 0, 100],
    ])('kẹp %j thành page %i, size %i', async (params, page, size) => {
      expect((await listOf(await chain(), params)).data.data).toMatchObject({ page, size });
    });
  });

  it('cùng một createdAt thì mã lớn hơn đứng trước', async () => {
    const at = '2026-10-20T03:00:00.000Z';
    inject([rec({ feedbackId: 1, createdAt: at }), rec({ feedbackId: 3, createdAt: at }), rec({ feedbackId: 2, createdAt: at })]);
    expect(idsOf(await listOf(await chain()))).toEqual([3, 2, 1]);
  });
});

describe('GET /api/feedbacks/:feedbackId (#21; BR-DG-03, 04, A2: mở lần đầu NEW → SEEN)', () => {
  it('mở feedback NEW: SEEN, ghi seenAt là bây giờ; danh sách cũng đổi', async () => {
    const who = await manager();
    expect(idsOf(await listOf(who, { status: 'NEW' }))).toEqual([3]);
    const res = await open(who, 3);
    expect(res.status).toBe(200);
    expect(Object.keys(res.data.data).sort()).toEqual(FULL_KEYS);
    expect(res.data.data).toMatchObject({ feedbackId: 3, status: 'SEEN', seenAt: '2026-10-27T03:00:00.000Z', customerName: 'Nguyễn Thu Trang', branchName: 'PetCare Cầu Giấy' });
    expect(idsOf(await listOf(who, { status: 'NEW' }))).toEqual([]);
    expect((await listOf(who)).data.data.content.find((f: { feedbackId: number }) => f.feedbackId === 3).status).toBe('SEEN');
  });

  it('mở lại (kể cả người khác, sau nhiều phút) không đổi seenAt', async () => {
    await open(await manager(), 3);
    setNow('2026-10-27T10:05:00+07:00');
    const again = await open(await chain(), 3);
    expect(again.data.data).toMatchObject({ status: 'SEEN', seenAt: '2026-10-27T03:00:00.000Z' });
  });

  it('mở feedback SEEN hoặc RESOLVED không ghi gì: state nguyên văn, seenAt giữ như cũ', async () => {
    const who = await chain();
    await open(who, 3); // ghi state lần đầu
    const seenBefore = (await listOf(who)).data.data.content.find((f: { feedbackId: number }) => f.feedbackId === 4).seenAt;
    const stateBefore = localStorage.getItem(FEEDBACK_STATE_KEY);
    setNow('2026-10-27T11:00:00+07:00');
    expect((await open(who, 4)).data.data.seenAt).toBe(seenBefore); // SEEN mẫu
    await open(who, 1); // RESOLVED mẫu
    expect(localStorage.getItem(FEEDBACK_STATE_KEY)).toBe(stateBefore);
  });

  it('404 khi không có; id không phải số: 400 MALFORMED_REQUEST', async () => {
    expectFail(await failure(open(await chain(), 9999)), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy feedback #9999');
    expectFail(await failure(open(await chain(), 0)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(open(await chain(), 'abc')), 400, 'MALFORMED_REQUEST', "Tham số 'feedbackId' sai kiểu dữ liệu");
  });

  describe('phạm vi (BR-DG-03): ngoài phạm vi là 403 và KHÔNG chuyển NEW → SEEN', () => {
    it('BRANCH_MANAGER không mở được feedback không gắn chi nhánh (WEBSITE #2, OTHER #5)', async () => {
      const who = await manager();
      expectFail(await failure(open(who, 2)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
      expectFail(await failure(open(who, 5)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
      expect((await open(await chain(), 5)).data.data.status).toBe('SEEN'); // chỉ lần mở của SUPER_MANAGER mới đổi
    });

    it('BRANCH_MANAGER chi nhánh khác không mở được feedback của chi nhánh 1; feedback vẫn NEW', async () => {
      expectFail(await failure(open(await manager2(), 3)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
      expect(idsOf(await listOf(await chain(), { status: 'NEW' }))).toEqual([3, 5]);
    });

    it('404 đến trước 403: id lạ luôn 404 với quản lý chi nhánh', async () => {
      expectFail(await failure(open(await manager2(), 9999)), 404, 'RESOURCE_NOT_FOUND');
    });
  });

  it('chỉ SUPER_MANAGER và BRANCH_MANAGER: khách, lễ tân, ADMIN 403 (vai trò đến trước 404); không token 401', async () => {
    for (const who of [await customer(), await desk(), await admin()]) {
      expectFail(await failure(open(who, 3)), 403, 'ACCESS_DENIED');
      expectFail(await failure(open(who, 9999)), 403, 'ACCESS_DENIED');
    }
    expectFail(await failure(client.get('/api/feedbacks/3')), 401, 'UNAUTHENTICATED');
    expect(idsOf(await listOf(await chain(), { status: 'NEW' }))).toEqual([3, 5]);
  });
});

describe('POST /api/feedbacks/:feedbackId/resolve (#22; BR-DG-04)', () => {
  it('từ SEEN: RESOLVED, ghi người xử lý, lúc xử lý, ghi chú đã strip; seenAt giữ nguyên', async () => {
    const who = await manager();
    const seen = (await open(who, 3)).data.data.seenAt;
    setNow('2026-10-27T10:02:00+07:00');
    const res = await resolve(who, 3, { resolutionNote: '  Đã gọi lại khách xin lỗi.  ' });
    expect(res.status).toBe(200);
    expect(Object.keys(res.data.data).sort()).toEqual(FULL_KEYS);
    expect(res.data.data).toMatchObject({
      status: 'RESOLVED',
      resolvedBy: 3,
      resolvedAt: '2026-10-27T03:02:00.000Z',
      resolutionNote: 'Đã gọi lại khách xin lỗi.',
      seenAt: seen,
    });
    expect((await open(who, 3)).data.data).toMatchObject({ status: 'RESOLVED', resolvedBy: 3 });
  });

  it('SUPER_MANAGER xử lý được feedback không gắn chi nhánh; resolvedBy là mã người gọi', async () => {
    const res = await resolve(await chain(), 2); // #2 WEBSITE đang SEEN
    expect(res.data.data).toMatchObject({ feedbackId: 2, status: 'RESOLVED', resolvedBy: 2 });
  });

  it('chuẩn hóa NFC ghi chú; đúng 2000 ký tự được nhận', async () => {
    const who = await chain();
    const note = 'Đã xử lý xong, khách hài lòng.'.normalize('NFD');
    expect((await resolve(who, 2, { resolutionNote: note })).data.data.resolutionNote).toBe(note.normalize('NFC'));
    expect((await resolve(who, 4, { resolutionNote: 'n'.repeat(2000) })).status).toBe(200);
  });

  describe('thứ tự: body 400 → vai trò 403 → 404 → phạm vi 403 → ghi chú trống 400 BR-DG-04 → trạng thái 409', () => {
    it('ghi chú trống sau strip: 400 BR-DG-04, vẫn SEEN', async () => {
      const who = await chain();
      const res = await failure(resolve(who, 2, { resolutionNote: '   ' }));
      expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', 'Vui lòng nhập ghi chú xử lý (BR-DG-04)');
      expect(ruleOf(res)).toBe('BR-DG-04');
      expect((await open(who, 2)).data.data).toMatchObject({ status: 'SEEN', resolutionNote: null, resolvedBy: null });
    });

    it('thiếu resolutionNote hoặc quá 2000 đơn vị UTF-16: 400 VALIDATION_FAILED theo từng trường', async () => {
      const who = await chain();
      expectFail(await failure(resolve(who, 2, {})), 400, 'VALIDATION_FAILED', 'resolutionNote: Vui lòng nhập ghi chú xử lý');
      expectFail(await failure(resolve(who, 2, { resolutionNote: 'n'.repeat(2001) })), 400, 'VALIDATION_FAILED', 'resolutionNote: Ghi chú tối đa 2000 ký tự');
      // @Size của BE đếm đơn vị UTF-16 (khác nội dung feedback đếm code point): 1001 emoji = 2002 đơn vị.
      expectFail(await failure(resolve(who, 2, { resolutionNote: '😀'.repeat(1001) })), 400, 'VALIDATION_FAILED', 'resolutionNote: Ghi chú tối đa 2000 ký tự');
      expectFail(await failure(resolve(who, 2, { resolutionNote: 123 })), 400, 'MALFORMED_REQUEST');
      expectFail(await failure(resolve(who, 'abc')), 400, 'MALFORMED_REQUEST', "Tham số 'feedbackId' sai kiểu dữ liệu");
    });

    it('feedback còn NEW: ghi chú hợp lệ nhận 409; ghi chú trống nhận 400 BR-DG-04 (trống đến trước trạng thái)', async () => {
      const who = await chain();
      expectFail(await failure(resolve(who, 3)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái Feedback từ NEW sang RESOLVED');
      expect(ruleOf(await failure(resolve(who, 3, { resolutionNote: ' ' })))).toBe('BR-DG-04');
      expect(idsOf(await listOf(who, { status: 'NEW' }))).toEqual([3, 5]);
    });

    it('đã RESOLVED: 409 "từ RESOLVED sang RESOLVED", người xử lý và ghi chú cũ giữ nguyên', async () => {
      const who = await manager();
      const before = (await open(who, 1)).data.data;
      expectFail(await failure(resolve(who, 1, { resolutionNote: 'Ghi đè' })), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái Feedback từ RESOLVED sang RESOLVED');
      expect((await open(who, 1)).data.data).toEqual(before);
    });

    it('body sai đến trước phạm vi; phạm vi đến trước ghi chú trống', async () => {
      const who = await manager();
      expectFail(await failure(resolve(who, 2, {})), 400, 'VALIDATION_FAILED'); // #2 không gắn chi nhánh, nhưng body sai đến trước
      expectFail(await failure(resolve(who, 2, { resolutionNote: ' ' })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    });

    it('404 khi không có feedback', async () => {
      expectFail(await failure(resolve(await chain(), 9999)), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy feedback #9999');
    });
  });

  describe('phạm vi: ngoài phạm vi là 403 và không ghi gì', () => {
    it('BRANCH_MANAGER chi nhánh 1 với feedback không gắn chi nhánh; chi nhánh 2 với feedback chi nhánh 1', async () => {
      expectFail(await failure(resolve(await manager(), 2)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
      expectFail(await failure(resolve(await manager2(), 4)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
      expect((await open(await chain(), 2)).data.data.status).toBe('SEEN');
      expect((await open(await chain(), 4)).data.data.status).toBe('SEEN');
    });
  });

  it('chỉ SUPER_MANAGER và BRANCH_MANAGER: khách, lễ tân, ADMIN 403; không token 401', async () => {
    for (const who of [await customer(), await desk(), await admin()]) {
      expectFail(await failure(resolve(who, 4)), 403, 'ACCESS_DENIED');
    }
    expectFail(await failure(client.post('/api/feedbacks/4/resolve', { resolutionNote: 'x' })), 401, 'UNAUTHENTICATED');
    expect((await open(await chain(), 4)).data.data.status).toBe('SEEN');
  });
});

describe('state localStorage cũ (trình duyệt dev giữ state từ bản trước)', () => {
  it('state rỗng {} (thiếu feedbacks): không throw, dùng dữ liệu mẫu', async () => {
    localStorage.setItem(FEEDBACK_STATE_KEY, '{}');
    expect(idsOf(await listOf(await chain()))).toEqual([3, 5, 4, 2, 6, 1]);
  });

  it('JSON hỏng: dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem(FEEDBACK_STATE_KEY, '{không phải json');
    expect(idsOf(await listOf(await chain()))).toHaveLength(6);
  });

  it('bản ghi thiếu các trường thêm sau (branchId, rating, seenAt, resolved*) được gộp mặc định; phần cũ còn nguyên', async () => {
    localStorage.setItem(
      FEEDBACK_STATE_KEY,
      JSON.stringify({ feedbacks: [{ feedbackId: 11, customerId: 1, topic: 'WEBSITE', content: 'Bản ghi từ bản cũ của mock.', status: 'NEW', createdAt: '2026-10-20T03:00:00.000Z' }] }),
    );
    const who = await chain();
    const listed = (await listOf(who)).data.data.content[0];
    expect(listed).toMatchObject({ feedbackId: 11, content: 'Bản ghi từ bản cũ của mock.', branchId: null, rating: null, seenAt: null, resolvedBy: null, resolvedAt: null, resolutionNote: null });
    expect(Object.keys(listed).sort()).toEqual(FULL_KEYS);
    expect((await open(who, 11)).data.data.status).toBe('SEEN');
    expect((await resolve(who, 11)).data.data.status).toBe('RESOLVED');
    expect((await mine(await customer())).map((f) => f.feedbackId)).toEqual([11]);
    expect((await submit(await customer())).data.data.feedbackId).toBe(12);
  });
});
