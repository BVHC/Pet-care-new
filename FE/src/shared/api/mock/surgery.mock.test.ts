import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PageResponse } from '../../types';
import type { Surgery, SurgerySummary } from '../../types/surgery';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (surgery.mock.ts): chi nhánh 1 có lịch sử #1 COMPLETED (lượt 2001), #2 CANCELLED (lượt 61) và KHÔNG có phẫu thuật đang mở; #3 PLANNED ở chi nhánh 2 (lượt giả 9102).
// Lượt 2002 (Lu, IN_PROGRESS) do BS. Minh Anh (doctor@store1.vn, #5) phụ trách; doctor2@store1.vn (#21) cùng chi nhánh nhưng không phụ trách.

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

type Auth = { headers: Record<string, string> };
async function as(email: string): Promise<Auth> {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const ROLES = {
  ADMIN: 'admin@petcare.vn',
  SUPER_MANAGER: 'chuoi@petcare.vn',
  BRANCH_MANAGER: 'manager@store1.vn',
  RECEPTIONIST: 'reception@store1.vn',
  VET: 'doctor@store1.vn',
  CARETAKER: 'caretaker@store1.vn',
  CUSTOMER: 'khach@petcare.vn',
} as const;
type RoleName = keyof typeof ROLES;

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const CONSENT = { consentText: 'Rủi ro gây mê, chi phí dự kiến 1.800.000 ₫, mổ đường giữa bụng', signerName: 'Lê Hoàng Long', signerRelation: 'Chủ thú' };
const data = <T>(res: AxiosResponse) => res.data.data as T;

const vet = () => as(ROLES.VET);
const desk = () => as(ROLES.RECEPTIONIST);
const plan = async (auth: Auth, body: object = { serviceId: 7001, planNote: 'Triệt sản chó đực' }, visitId = 2002) =>
  data<Surgery>(await client.post(`/api/visits/${visitId}/surgeries`, body, auth));
const act = async (auth: Auth, surgeryId: number, action: string, body: object = {}) =>
  data<Surgery>(await client.post(`/api/surgeries/${surgeryId}/${action}`, body, auth));
/** Phẫu thuật mới ở lượt 2002 đi tới trạng thái `upTo`. */
async function advance(upTo: 'PLANNED' | 'CONSENTED' | 'IN_PROGRESS' | 'COMPLETED'): Promise<Surgery> {
  const doctor = await vet();
  let s = await plan(doctor);
  if (upTo === 'PLANNED') return s;
  s = await act(await desk(), s.surgeryId, 'consent', CONSENT);
  if (upTo === 'CONSENTED') return s;
  s = await act(doctor, s.surgeryId, 'start');
  if (upTo === 'IN_PROGRESS') return s;
  return act(doctor, s.surgeryId, 'complete', { resultNote: 'Ổn định, không biến chứng' });
}
const list = async (auth: Auth, params: object = {}) => data<PageResponse<SurgerySummary>>(await client.get('/api/surgeries', { params, ...auth }));

// ─── Quyền (đúng dòng "Quyền:" của yaml) và 401 ───

interface Endpoint {
  name: string;
  call: (auth: Auth) => Promise<unknown>;
  allowed: RoleName[];
}
const ENDPOINTS: Endpoint[] = [
  { name: '#1 lập kế hoạch', call: (a) => client.post('/api/visits/2002/surgeries', { serviceId: 7001 }, a), allowed: ['VET'] },
  { name: '#2 phẫu thuật của lượt', call: (a) => client.get('/api/visits/2002/surgeries', a), allowed: ['RECEPTIONIST', 'VET', 'CARETAKER'] },
  { name: '#3 của chi nhánh', call: (a) => client.get('/api/surgeries', a), allowed: ['RECEPTIONIST', 'VET'] },
  { name: '#4 chi tiết', call: (a) => client.get('/api/surgeries/1', a), allowed: ['RECEPTIONIST', 'VET', 'CARETAKER'] },
  { name: '#5 cam kết', call: (a) => client.post('/api/surgeries/1/consent', CONSENT, a), allowed: ['RECEPTIONIST'] },
  { name: '#6 bắt đầu', call: (a) => client.post('/api/surgeries/1/start', {}, a), allowed: ['VET'] },
  { name: '#7 hoàn tất', call: (a) => client.post('/api/surgeries/1/complete', { resultNote: 'Ổn' }, a), allowed: ['VET'] },
  { name: '#8 hủy', call: (a) => client.post('/api/surgeries/1/cancel', { reason: 'Khách đổi ý' }, a), allowed: ['VET', 'RECEPTIONIST'] },
];

describe('quyền theo 7 vai trò và 401 (mọi endpoint)', () => {
  it.each(ENDPOINTS)('$name: vai trò ngoài "Quyền:" nhận đúng 403 ACCESS_DENIED, vai trò được phép thì không', async ({ call, allowed }) => {
    for (const [role, email] of Object.entries(ROLES) as [RoleName, string][]) {
      const auth = await as(email);
      if (allowed.includes(role)) {
        // Được phép: có thể thành công hoặc rớt ở bước sau (404 / phạm vi / rule / 409), nhưng không phải lỗi vai trò.
        const res = await call(auth).then(
          (r) => r as AxiosResponse,
          (e) => (axios.isAxiosError(e) && e.response ? e.response : Promise.reject(e)),
        );
        expect(res.data.errorCode, role).not.toBe('ACCESS_DENIED');
      } else {
        const res = await failure(call(auth));
        expect([role, res.status, res.data.errorCode]).toEqual([role, 403, 'ACCESS_DENIED']);
      }
    }
  });

  it.each(ENDPOINTS)('$name: không token → 401 UNAUTHENTICATED', async ({ call }) => {
    const res = await failure(call({ headers: {} }));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
    expect(Object.keys(res.data).sort()).toEqual(['errorCode', 'message', 'statusCode', 'success', 'timestamp', 'traceId']);
  });
});

// ─── #1 Lập kế hoạch ───

describe('lập kế hoạch phẫu thuật (#1, BR-KB-08)', () => {
  it('VET phụ trách lượt đang khám lập được: 201, PLANNED, đủ trường, tên thú và dịch vụ', async () => {
    const res = await client.post('/api/visits/2002/surgeries', { serviceId: 7001, planNote: '  Triệt sản chó đực  ' }, await vet());
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã lập kế hoạch phẫu thuật', code: 201 });
    expect(res.data.data).toMatchObject({
      visitId: 2002,
      petId: 4,
      petName: 'Lu',
      branchId: 1,
      serviceId: 7001,
      serviceName: 'Triệt sản chó mèo',
      status: 'PLANNED',
      planNote: 'Triệt sản chó đực',
      consentText: null,
      consentSignerName: null,
      consentedAt: null,
      startedAt: null,
      completedAt: null,
      resultNote: null,
      cancelReason: null,
      orderLineId: null,
    });
    expect(res.data.data.surgeryId).toBeGreaterThan(3);
    expect(Date.parse(res.data.data.createdAt)).not.toBeNaN();
  });

  it('planNote vắng hoặc toàn khoảng trắng → null', async () => {
    const doctor = await vet();
    expect((await plan(doctor, { serviceId: 7001 })).planNote).toBeNull();
    expect((await plan(doctor, { serviceId: 7001, planNote: '   ' })).planNote).toBeNull();
  });

  it('báo mọi lễ tân của chi nhánh bằng thông báo SURGERY_PLANNED; quản lý, khách không nhận', async () => {
    await plan(await vet());
    const unread = async (email: string) =>
      data<PageResponse<{ type: string; title: string }>>(await client.get('/api/me/notifications', { params: { unreadOnly: true }, ...(await as(email)) })).content;
    expect((await unread('reception@store1.vn')).filter((n) => n.type === 'SURGERY_PLANNED')).toMatchObject([{ title: 'Cần lập cam kết phẫu thuật cho Lu' }]);
    expect((await unread('manager@store1.vn')).some((n) => n.type === 'SURGERY_PLANNED')).toBe(false);
    expect((await unread('khach@petcare.vn')).some((n) => n.type === 'SURGERY_PLANNED')).toBe(false);
  });

  it('thứ tự: body / path sai kiểu 400 (trước cả vai trò) → vai trò 403 → độ dài 400 → Visit 404 → không phụ trách 403 → dịch vụ 404 → Visit không IN_PROGRESS 400 → dịch vụ 400', async () => {
    const customer = await as(ROLES.CUSTOMER);
    expect((await failure(client.post('/api/visits/2002/surgeries', { serviceId: 'abc' }, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/visits/2002/surgeries', { serviceId: 7001, planNote: [] }, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/visits/abc/surgeries', { serviceId: 7001 }, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/visits/2002/surgeries', undefined, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/visits/2002/surgeries', { serviceId: 7001 }, customer))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/visits/2002/surgeries', {}, customer))).data.errorCode).toBe('ACCESS_DENIED');

    const doctor = await vet();
    const missing = await failure(client.post('/api/visits/2002/surgeries', {}, doctor));
    expect(missing).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('serviceId') } });
    expect((await failure(client.post('/api/visits/2002/surgeries', { serviceId: 0 }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    const longNote = await failure(client.post('/api/visits/2002/surgeries', { serviceId: 7001, planNote: 'x'.repeat(501) }, doctor));
    expect(longNote).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('planNote') } });
    // Độ dài (400) đứng trước 404 của Visit.
    expect((await failure(client.post('/api/visits/9999/surgeries', { serviceId: 7001, planNote: 'x'.repeat(501) }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');

    expect((await failure(client.post('/api/visits/9999/surgeries', { serviceId: 7001 }, doctor))).status).toBe(404);
    // Lượt chưa gán (2101) và lượt của VET khác (doctor2 trên 2002): 403 phạm vi, message chung, không mã rule.
    for (const [auth, visitId] of [[doctor, 2101], [await as('doctor2@store1.vn'), 2002]] as const) {
      const denied = await failure(client.post(`/api/visits/${visitId}/surgeries`, { serviceId: 7001 }, auth));
      expect(denied).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
      expect(denied.data.message).not.toMatch(/\(BR-/);
    }
    // Lượt 2102 (Tôm, WAITING) do VET #5 phụ trách: dịch vụ 404 đứng trước "Visit không IN_PROGRESS".
    expect((await failure(client.post('/api/visits/2102/surgeries', { serviceId: 99999 }, doctor))).status).toBe(404);
    const waiting = await failure(client.post('/api/visits/2102/surgeries', { serviceId: 7001 }, doctor));
    expect(waiting).toMatchObject({ status: 400, data: { errorCode: 'BUSINESS_RULE_VIOLATION', message: expect.stringMatching(/\(BR-KB-08\)$/) } });
  });

  it('dịch vụ không phải SURGERY (khám, tiêm), đã ngừng, chỉ bật ở chi nhánh khác: 400 BR-KB-08 và không ghi gì', async () => {
    const doctor = await vet();
    for (const serviceId of [1, 2, 7004, 7003, 7101]) {
      const res = await failure(client.post('/api/visits/2002/surgeries', { serviceId }, doctor));
      expect([serviceId, res.status, res.data.errorCode]).toEqual([serviceId, 400, 'BUSINESS_RULE_VIOLATION']);
      expect(res.data.message).toMatch(/\(BR-KB-08\)$/);
    }
    expect(data<Surgery[]>(await client.get('/api/visits/2002/surgeries', doctor))).toEqual([]);
  });

  it('một lượt có nhiều phẫu thuật (hợp đồng không giới hạn); mã tăng dần', async () => {
    const doctor = await vet();
    const a = await plan(doctor);
    const b = await plan(doctor, { serviceId: 7002 });
    expect(b.surgeryId).toBe(a.surgeryId + 1);
    expect(data<Surgery[]>(await client.get('/api/visits/2002/surgeries', doctor)).map((s) => s.surgeryId)).toEqual([a.surgeryId, b.surgeryId]);
  });
});

// ─── #2, #3, #4 Xem ───

describe('xem phẫu thuật (#2, #3, #4)', () => {
  it('#2: lượt 2001 có phẫu thuật mẫu #1 đủ trường; lượt chưa có gì trả mảng rỗng; CARETAKER xem được', async () => {
    const res = data<Surgery[]>(await client.get('/api/visits/2001/surgeries', await as(ROLES.CARETAKER)));
    expect(res).toHaveLength(1);
    expect(res[0]).toMatchObject({
      surgeryId: 1,
      status: 'COMPLETED',
      petName: 'Mochi',
      serviceName: 'Triệt sản chó mèo',
      consentSignerName: 'Nguyễn Thu Trang',
      consentSignerRelation: 'Chủ thú',
      orderLineId: 5001,
    });
    expect(res[0].resultNote).toContain('thuận lợi');
    expect(data<Surgery[]>(await client.get('/api/visits/2101/surgeries', await vet()))).toEqual([]);
  });

  it('#2: path sai kiểu 400, Visit không có 404, Visit không phải chi nhánh mình 403', async () => {
    const doctor = await vet();
    expect((await failure(client.get('/api/visits/abc/surgeries', doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/visits/9999/surgeries', doctor))).status).toBe(404);
    expect((await failure(client.get('/api/visits/abc/surgeries', await as(ROLES.CUSTOMER)))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('#2: nhiều phẫu thuật của một lượt: cũ nhất trước', async () => {
    const doctor = await vet();
    const a = await plan(doctor);
    const b = await plan(doctor, { serviceId: 7002 });
    const rows = data<Surgery[]>(await client.get('/api/visits/2002/surgeries', doctor));
    expect(rows.map((s) => s.surgeryId)).toEqual([a.surgeryId, b.surgeryId]);
  });

  it('#3: mặc định (vắng status) chỉ gồm PLANNED, CONSENTED, IN_PROGRESS của chi nhánh mình, cũ nhất trước; rỗng khi chưa có việc', async () => {
    expect(await list(await desk())).toMatchObject({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const planned = await advance('PLANNED');
    const consented = await advance('CONSENTED');
    const inProgress = await advance('IN_PROGRESS');
    await advance('COMPLETED');
    const page = await list(await desk());
    expect(page.content.map((s) => [s.surgeryId, s.status])).toEqual([
      [planned.surgeryId, 'PLANNED'],
      [consented.surgeryId, 'CONSENTED'],
      [inProgress.surgeryId, 'IN_PROGRESS'],
    ]);
    expect(page.content[0]).toEqual({
      surgeryId: planned.surgeryId,
      visitId: 2002,
      petName: 'Lu',
      serviceName: 'Triệt sản chó mèo',
      status: 'PLANNED',
      createdAt: planned.createdAt,
    });
    expect(page.totalElements).toBe(3);
  });

  it('#3: lọc status, kể cả lịch sử; phẫu thuật chi nhánh 2 (#3) không bao giờ lộ; status sai → 400; phân trang kẹp page / size', async () => {
    const auth = await vet();
    expect((await list(auth, { status: 'COMPLETED' })).content.map((s) => s.surgeryId)).toEqual([1]);
    expect((await list(auth, { status: 'CANCELLED' })).content.map((s) => s.surgeryId)).toEqual([2]);
    expect((await list(auth, { status: 'PLANNED' })).totalElements).toBe(0);
    expect((await failure(client.get('/api/surgeries', { params: { status: 'DONE' }, ...auth }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/surgeries', { params: { page: 'x' }, ...auth }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/surgeries', { params: { size: 'x' }, ...(await as(ROLES.CUSTOMER)) }))).data.errorCode).toBe('MALFORMED_REQUEST');
    await plan(auth);
    await plan(auth, { serviceId: 7002 });
    expect(await list(auth, { size: 1, page: 1 })).toMatchObject({ page: 1, size: 1, totalElements: 2, totalPages: 2 });
    expect(await list(auth, { page: -4, size: 0 })).toMatchObject({ page: 0, size: 1, totalPages: 2 });
    expect((await list(auth, { size: 500 })).size).toBe(100);
  });

  it('#4: chi tiết; không có 404; chi nhánh khác 403 phạm vi (không có mã rule); path sai kiểu 400', async () => {
    const doctor = await vet();
    expect(data<Surgery>(await client.get('/api/surgeries/2', doctor))).toMatchObject({
      status: 'CANCELLED',
      cancelReason: 'Khách cân nhắc chi phí, chưa đồng ý phẫu thuật',
      serviceName: 'Phẫu thuật mô mềm',
      resultNote: null,
    });
    expect((await failure(client.get('/api/surgeries/999', doctor))).status).toBe(404);
    const other = await failure(client.get('/api/surgeries/3', doctor));
    expect(other).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
    expect(other.data.message).not.toMatch(/\(BR-/);
    expect((await failure(client.get('/api/surgeries/abc', doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

// ─── #5 Cam kết ───

describe('lập cam kết (#5, BR-KB-09)', () => {
  it('lễ tân lập: PLANNED → CONSENTED, nội dung được cắt khoảng trắng và là bản chụp', async () => {
    const s = await advance('PLANNED');
    const done = await act(await desk(), s.surgeryId, 'consent', { consentText: `  ${CONSENT.consentText}  `, signerName: ' Lê Hoàng Long ', signerRelation: ' Chủ thú ' });
    expect(done).toMatchObject({
      status: 'CONSENTED',
      consentText: CONSENT.consentText,
      consentSignerName: 'Lê Hoàng Long',
      consentSignerRelation: 'Chủ thú',
    });
    expect(Date.parse(done.consentedAt!)).not.toBeNaN();
    expect((await failure(client.post(`/api/surgeries/${s.surgeryId}/consent`, { ...CONSENT, consentText: 'Sửa lại' }, await desk()))).status).toBe(409);
    expect(data<Surgery>(await client.get(`/api/surgeries/${s.surgeryId}`, await desk())).consentText).toBe(CONSENT.consentText);
  });

  it('thứ tự: body sai kiểu 400 (trước vai trò) → vai trò 403 → độ dài 400 → 404 → chi nhánh 403 → thiếu nội dung / người ký 400 BR-KB-09 → 409', async () => {
    const s = await advance('PLANNED');
    const doctor = await vet();
    const url = `/api/surgeries/${s.surgeryId}/consent`;
    expect((await failure(client.post(url, { ...CONSENT, signerName: {} }, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, undefined, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, CONSENT, doctor))).data.errorCode).toBe('ACCESS_DENIED');
    const reception = await desk();
    const longName = await failure(client.post(url, { ...CONSENT, signerName: 'x'.repeat(101) }, reception));
    expect(longName).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('signerName') } });
    expect((await failure(client.post(url, { ...CONSENT, signerRelation: 'x'.repeat(51) }, reception))).data.message).toContain('signerRelation');
    expect((await failure(client.post('/api/surgeries/999/consent', { ...CONSENT, signerName: 'x'.repeat(101) }, reception))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/surgeries/999/consent', CONSENT, reception))).status).toBe(404);
    expect((await failure(client.post('/api/surgeries/3/consent', CONSENT, reception))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Trống / toàn khoảng trắng / thiếu: BR-KB-09 (không phải VALIDATION_FAILED), và đứng trước 409 (phẫu thuật #1 đã COMPLETED).
    for (const patch of [{ consentText: '' }, { consentText: '   ' }, { signerName: ' ' }, { signerRelation: null }, { signerName: undefined }]) {
      const res = await failure(client.post(url, { ...CONSENT, ...patch }, reception));
      expect([res.status, res.data.errorCode, res.data.message.endsWith('(BR-KB-09)')]).toEqual([400, 'BUSINESS_RULE_VIOLATION', true]);
    }
    expect((await failure(client.post('/api/surgeries/1/consent', { ...CONSENT, consentText: '' }, reception))).status).toBe(400);
    const gone = await failure(client.post('/api/surgeries/1/consent', CONSENT, reception));
    expect(gone).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    await act(reception, s.surgeryId, 'consent', CONSENT);
    expect((await failure(client.post(url, CONSENT, reception))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── #6 Bắt đầu, #7 Hoàn tất ───

describe('bắt đầu phẫu thuật (#6, BR-KB-09, 10)', () => {
  it('CONSENTED → IN_PROGRESS, ghi startedAt', async () => {
    const s = await advance('CONSENTED');
    const started = await act(await vet(), s.surgeryId, 'start');
    expect(started.status).toBe('IN_PROGRESS');
    expect(Date.parse(started.startedAt!)).not.toBeNaN();
  });

  it('thứ tự: vai trò → 404 → chi nhánh 403 → không phụ trách 403 → chưa có cam kết 400 BR-KB-09 → trạng thái khác 409', async () => {
    const planned = await advance('PLANNED');
    const doctor = await vet();
    const other = await as('doctor2@store1.vn');
    expect((await failure(client.post('/api/surgeries/abc/start', {}, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/surgeries/999/start', {}, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/surgeries/3/start', {}, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // VET cùng chi nhánh nhưng không phụ trách Visit: 403 phạm vi đứng trước 400 chưa cam kết.
    expect((await failure(client.post(`/api/surgeries/${planned.surgeryId}/start`, {}, other))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    const early = await failure(client.post(`/api/surgeries/${planned.surgeryId}/start`, {}, doctor));
    expect(early).toMatchObject({ status: 400, data: { errorCode: 'BUSINESS_RULE_VIOLATION', message: expect.stringMatching(/\(BR-KB-09\)$/) } });
    for (const id of [1, 2]) {
      expect((await failure(client.post(`/api/surgeries/${id}/start`, {}, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    }
    await act(await desk(), planned.surgeryId, 'consent', CONSENT);
    await act(doctor, planned.surgeryId, 'start');
    expect((await failure(client.post(`/api/surgeries/${planned.surgeryId}/start`, {}, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

describe('hoàn tất phẫu thuật (#7, BR-KB-10)', () => {
  it('IN_PROGRESS → COMPLETED: ghi kết quả, completedAt và orderLineId (dịch vụ vào Order của Visit)', async () => {
    const s = await advance('IN_PROGRESS');
    expect(s.orderLineId).toBeNull();
    const done = await act(await vet(), s.surgeryId, 'complete', { resultNote: '  Ổn định, hẹn cắt chỉ sau 10 ngày  ' });
    expect(done).toMatchObject({ status: 'COMPLETED', resultNote: 'Ổn định, hẹn cắt chỉ sau 10 ngày' });
    expect(Number.isInteger(done.orderLineId)).toBe(true);
    expect(Date.parse(done.completedAt!)).not.toBeNaN();
  });

  it('thứ tự: body sai kiểu 400 → vai trò → 404 → chi nhánh / không phụ trách 403 → thiếu ghi chú kết quả 400 BR-KB-10 → chưa bắt đầu 409', async () => {
    const consented = await advance('CONSENTED');
    const doctor = await vet();
    const url = `/api/surgeries/${consented.surgeryId}/complete`;
    expect((await failure(client.post(url, { resultNote: [] }, await as(ROLES.CUSTOMER)))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/surgeries/999/complete', { resultNote: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/surgeries/3/complete', { resultNote: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post(url, { resultNote: 'x' }, await as('doctor2@store1.vn')))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    for (const body of [{}, { resultNote: '' }, { resultNote: '   ' }, { resultNote: null }]) {
      const res = await failure(client.post(url, body, doctor));
      expect([res.status, res.data.message.endsWith('(BR-KB-10)')]).toEqual([400, true]);
    }
    // Ghi chú hợp lệ nhưng phẫu thuật mới CONSENTED / đã COMPLETED: 409.
    expect((await failure(client.post(url, { resultNote: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/surgeries/1/complete', { resultNote: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── #8 Hủy / dừng ───

describe('hủy hoặc dừng phẫu thuật (#8, BR-KB-11)', () => {
  it('VET hủy ở PLANNED, CONSENTED và dừng ở IN_PROGRESS; lễ tân hủy ở PLANNED, CONSENTED', async () => {
    const doctor = await vet();
    const reception = await desk();
    expect(await act(doctor, (await advance('PLANNED')).surgeryId, 'cancel', { reason: '  Khách đổi ý  ' })).toMatchObject({ status: 'CANCELLED', cancelReason: 'Khách đổi ý' });
    expect((await act(doctor, (await advance('CONSENTED')).surgeryId, 'cancel', { reason: 'Thú sốt cao' })).status).toBe('CANCELLED');
    expect((await act(doctor, (await advance('IN_PROGRESS')).surgeryId, 'cancel', { reason: 'Thú tụt huyết áp, dừng mổ' })).orderLineId).toBeNull();
    expect((await act(reception, (await advance('PLANNED')).surgeryId, 'cancel', { reason: 'Khách từ chối' })).status).toBe('CANCELLED');
    expect((await act(reception, (await advance('CONSENTED')).surgeryId, 'cancel', { reason: 'Khách từ chối' })).status).toBe('CANCELLED');
  });

  it('lễ tân không hủy được phẫu thuật IN_PROGRESS: 403 ACCESS_DENIED, trạng thái giữ nguyên; COMPLETED thì 409', async () => {
    const s = await advance('IN_PROGRESS');
    const reception = await desk();
    const res = await failure(client.post(`/api/surgeries/${s.surgeryId}/cancel`, { reason: 'Khách đổi ý' }, reception));
    expect(res).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED' } });
    expect(data<Surgery>(await client.get(`/api/surgeries/${s.surgeryId}`, reception)).status).toBe('IN_PROGRESS');
    expect((await failure(client.post('/api/surgeries/1/cancel', { reason: 'x' }, reception))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('thứ tự: body sai kiểu 400 → vai trò → độ dài 400 → 404 → chi nhánh / không phụ trách 403 → thiếu lý do 400 BR-KB-11 → 409', async () => {
    const s = await advance('CONSENTED');
    const doctor = await vet();
    const url = `/api/surgeries/${s.surgeryId}/cancel`;
    expect((await failure(client.post(url, { reason: {} }, await as(ROLES.CARETAKER)))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { reason: 'x' }, await as(ROLES.CARETAKER)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/surgeries/999/cancel', { reason: 'x'.repeat(301) }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/surgeries/999/cancel', { reason: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/surgeries/3/cancel', { reason: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post('/api/surgeries/3/cancel', { reason: 'x' }, await desk()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post(url, { reason: 'x' }, await as('doctor2@store1.vn')))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    for (const body of [{}, { reason: '' }, { reason: '  ' }, { reason: null }]) {
      const res = await failure(client.post(url, body, doctor));
      expect([res.status, res.data.message.endsWith('(BR-KB-11)')]).toEqual([400, true]);
    }
    expect((await failure(client.post('/api/surgeries/1/cancel', { reason: '' }, doctor))).status).toBe(400);
    expect((await failure(client.post('/api/surgeries/2/cancel', { reason: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── Hoàn tất lượt (visit-v1 #10) bị chặn bởi phẫu thuật mở (BR-KB-10) ───

describe('guard hoàn tất lượt khi còn phẫu thuật mở (BR-KB-10)', () => {
  it('còn PLANNED / CONSENTED / IN_PROGRESS: 400 BR-KB-10; hủy hoặc hoàn tất hết thì hoàn tất được', async () => {
    const doctor = await vet();
    await client.put('/api/visits/2002/medical-record', { diagnosis: 'Khỏe' }, doctor);
    const s = await advance('CONSENTED');
    for (const status of ['CONSENTED', 'IN_PROGRESS']) {
      if (status === 'IN_PROGRESS') await act(doctor, s.surgeryId, 'start');
      const blocked = await failure(client.post('/api/visits/2002/complete', undefined, doctor));
      expect(blocked).toMatchObject({ status: 400, data: { errorCode: 'BUSINESS_RULE_VIOLATION', message: expect.stringMatching(/\(BR-KB-10\)$/) } });
    }
    await act(doctor, s.surgeryId, 'complete', { resultNote: 'Xong' });
    const done = await client.post('/api/visits/2002/complete', undefined, doctor);
    expect(done.data.data).toMatchObject({ visitId: 2002, status: 'COMPLETED' });
    // Sau khi lượt đã hoàn tất không lập được phẫu thuật mới (BR-KB-08).
    expect((await failure(client.post('/api/visits/2002/surgeries', { serviceId: 7001 }, doctor))).data.message).toMatch(/\(BR-KB-08\)$/);
  });

  it('guard chạy sau 409 trạng thái và chỉ xét lượt có phẫu thuật mở: lượt khác hoàn tất bình thường', async () => {
    const doctor = await vet();
    await plan(doctor);
    const other = await failure(client.post('/api/visits/2102/complete', undefined, doctor));
    expect(other.data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── State lưu dạng cũ / thiếu trường ───

describe('state localStorage', () => {
  it('state rỗng {} (thiếu bảng) không làm route nổ: gộp dữ liệu mẫu và ghi lại', async () => {
    localStorage.setItem('petcare-mock-surgery', '{}');
    const page = await list(await vet(), { status: 'COMPLETED' });
    expect(page.content.map((s) => s.surgeryId)).toEqual([1]);
    expect(JSON.parse(localStorage.getItem('petcare-mock-surgery')!).surgeries).toHaveLength(3);
    // Mã tiếp theo không đè mã mẫu.
    expect((await plan(await vet())).surgeryId).toBe(4);
  });

  it('hàng thiếu trường: trường tùy chọn là null (không phải undefined), mã mới tiếp theo mã lớn nhất', async () => {
    localStorage.setItem(
      'petcare-mock-surgery',
      JSON.stringify({ surgeries: [{ surgeryId: 10, visitId: 2001, petId: 1, branchId: 1, serviceId: 7001, status: 'COMPLETED', plannedBy: 5, createdAt: '2026-10-01T03:00:00.000Z' }] }),
    );
    const row = data<Surgery>(await client.get('/api/surgeries/10', await vet()));
    expect(row).toMatchObject({ surgeryId: 10, planNote: null, consentText: null, resultNote: null, cancelReason: null, orderLineId: null });
    expect((await plan(await vet())).surgeryId).toBe(11);
  });

  it('JSON hỏng: dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-surgery', 'không phải json');
    expect((await list(await vet(), { status: 'CANCELLED' })).totalElements).toBe(1);
  });
});
