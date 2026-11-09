import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PageResponse } from '../../types';
import type { Admission, AdmissionOrder, AdmissionSummary, Observation } from '../../types/inpatient';
import type { Visit } from '../../types/visit';
import { registerVisitOrderSource } from './clinical-shared';
import { petCurrentWeight, petSummary } from './customer-pet.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { hasActiveAdmission } from './inpatient.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (inpatient.mock.ts): chi nhánh 1 có lịch sử #1 DISCHARGED (lượt 61, chuồng C-03, 3-4 y lệnh, 3 theo dõi) và KHÔNG có ca ADMITTED; #2 ADMITTED ở chi nhánh 2 (lượt giả 9103,
// 1 y lệnh, 1 theo dõi) để thử 403 phạm vi. Lượt 2002 (Lu, IN_PROGRESS) do BS. Minh Anh (doctor@store1.vn, #5) phụ trách. Đặt chỗ lưu trú mẫu CHECKED_IN: #2 Cốm (chuồng C-01), #3 Mực (C-02).

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
const data = <T>(res: AxiosResponse) => res.data.data as T;
const rule = (res: AxiosResponse, ruleId: string) => [res.status, res.data.errorCode, res.data.message.endsWith(`(${ruleId})`)];
const BAD_RULE = (ruleId: string) => [400, 'BUSINESS_RULE_VIOLATION', true, ruleId];
const asRule = (res: AxiosResponse, ruleId: string) => [...rule(res, ruleId), ruleId];

const vet = () => as(ROLES.VET);
const nurse = () => as(ROLES.CARETAKER);
const desk = () => as(ROLES.RECEPTIONIST);
const SOON = () => new Date(Date.now() + 3_600_000).toISOString();

const admit = async (auth: Auth, body: object = { visitId: 2002, serviceId: 7101 }) => data<Admission>(await client.post('/api/admissions', body, auth));
const order = async (auth: Auth, admissionId: number, body: object = { orderType: 'FEEDING', content: 'Cho ăn pate mềm', scheduledAt: SOON() }) =>
  data<AdmissionOrder>(await client.post(`/api/admissions/${admissionId}/orders`, body, auth));
const complete = async (auth: Auth, orderId: number, note = 'Đã làm') => data<AdmissionOrder>(await client.post(`/api/admission-orders/${orderId}/complete`, { note }, auth));
const discharge = (auth: Auth, admissionId: number, body: object = { outcome: 'HOME', summary: 'Ổn định, về nhà theo dõi' }) =>
  client.post(`/api/admissions/${admissionId}/discharge`, body, auth);
const list = async (auth: Auth, params: object = {}) => data<PageResponse<AdmissionSummary>>(await client.get('/api/admissions', { params, ...auth }));

/** Đọc / sửa state thô trong localStorage (mock đã nạp sau lần gọi đầu). */
const rawState = () => JSON.parse(localStorage.getItem('petcare-mock-inpatient')!) as { orders: { admissionOrderId: number; admissionId: number }[]; admissions: Record<string, unknown>[] };
const patchState = (fn: (s: ReturnType<typeof rawState>) => void) => {
  const s = rawState();
  fn(s);
  localStorage.setItem('petcare-mock-inpatient', JSON.stringify(s));
};
/** Đánh dấu thú đã mất thẳng vào state mock thú (mark-deceased thật bị chặn khi thú còn lượt mở: xem A7 trong inpatient.mock.ts). */
function markPetDeceased(petId: number) {
  petSummary(petId); // nạp state thú
  const s = JSON.parse(localStorage.getItem('petcare-mock-pets')!) as { pets: { petId: number; deceasedOn: string | null }[] };
  s.pets.find((p) => p.petId === petId)!.deceasedOn = '2026-11-01';
  localStorage.setItem('petcare-mock-pets', JSON.stringify(s));
}

// ─── Quyền (đúng dòng "Quyền:" của yaml) và 401 ───

interface Endpoint {
  name: string;
  call: (auth: Auth) => Promise<unknown>;
  allowed: RoleName[];
}
const VIEWERS: RoleName[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const ENDPOINTS: Endpoint[] = [
  { name: '#1 nhập viện', call: (a) => client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, a), allowed: ['VET'] },
  { name: '#2 ca của chi nhánh', call: (a) => client.get('/api/admissions', a), allowed: VIEWERS },
  { name: '#3 chi tiết ca', call: (a) => client.get('/api/admissions/1', a), allowed: VIEWERS },
  { name: '#4 xuất viện', call: (a) => client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: 'Ổn' }, a), allowed: ['VET'] },
  { name: '#5 ra y lệnh', call: (a) => client.post('/api/admissions/1/orders', { orderType: 'FEEDING', content: 'Cho ăn', scheduledAt: SOON() }, a), allowed: ['VET'] },
  { name: '#6 y lệnh của ca', call: (a) => client.get('/api/admissions/1/orders', a), allowed: VIEWERS },
  { name: '#7 thực hiện y lệnh', call: (a) => client.post('/api/admission-orders/1/complete', { note: 'Ổn' }, a), allowed: ['VET', 'CARETAKER'] },
  { name: '#8 hủy y lệnh', call: (a) => client.post('/api/admission-orders/1/cancel', { reason: 'Ổn' }, a), allowed: ['VET'] },
  { name: '#9 ghi theo dõi', call: (a) => client.post('/api/admissions/1/observations', { abnormal: false, note: 'Ổn' }, a), allowed: ['VET', 'CARETAKER'] },
  { name: '#10 theo dõi của ca', call: (a) => client.get('/api/admissions/1/observations', a), allowed: VIEWERS },
];

describe('quyền theo 7 vai trò và 401 (mọi endpoint)', () => {
  it.each(ENDPOINTS)('$name: vai trò ngoài "Quyền:" nhận đúng 403 ACCESS_DENIED, vai trò được phép thì không', async ({ call, allowed }) => {
    for (const [role, email] of Object.entries(ROLES) as [RoleName, string][]) {
      const auth = await as(email);
      if (allowed.includes(role)) {
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

// ─── #1 Nhập viện ───

describe('nhập viện (#1, BR-LT-13, 17)', () => {
  it('từ lượt đang khám của mình: 201, ADMITTED, giá chốt theo ngày, mã NT-yyMMdd-xxxx; chuồng vắng thì không có', async () => {
    const res = await client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, await vet());
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã nhập viện', code: 201 });
    expect(res.data.data).toMatchObject({
      visitId: 2002,
      petId: 4,
      petName: 'Lu',
      branchId: 1,
      boardingBookingId: null,
      kennelId: null,
      serviceId: 7101,
      serviceName: 'Nội trú điều trị (theo ngày)',
      unitPrice: 350_000,
      status: 'ADMITTED',
      dischargeOutcome: null,
      dischargeSummary: null,
      dischargedAt: null,
      orderLineId: null,
    });
    expect(res.data.data.code).toMatch(/^NT-\d{6}-\d{4}$/);
    expect(Date.parse(res.data.data.admittedAt)).not.toBeNaN();
    expect(hasActiveAdmission(4)).toBe(true);
    expect(hasActiveAdmission(1)).toBe(false);
  });

  it('mã tăng dần trong ngày; ca mới hiện ở danh sách ADMITTED của lễ tân kèm mã chuồng', async () => {
    const a = await admit(await vet(), { visitId: 2002, serviceId: 7101, kennelId: 3 });
    expect(a.kennelId).toBe(3);
    const b = await admit(await vet(), { boardingBookingId: 2, serviceId: 7102 });
    expect(Number(b.code.slice(-4))).toBe(Number(a.code.slice(-4)) + 1);
    const page = await list(await desk());
    expect(page.content.map((s) => [s.admissionId, s.petName, s.kennelCode])).toEqual([
      [a.admissionId, 'Lu', 'C-03'],
      [b.admissionId, 'Cốm', 'C-01'],
    ]);
  });

  it('từ đặt chỗ lưu trú CHECKED_IN: tạo Visit điều trị IN_PROGRESS gán VET gọi, giữ chuồng đang ở, đặt chỗ vẫn CHECKED_IN (BR-LT-17)', async () => {
    const doctor = await vet();
    const a = await admit(doctor, { boardingBookingId: 2, serviceId: 7101 });
    expect(a).toMatchObject({ boardingBookingId: 2, petId: 9002, petName: 'Cốm', kennelId: 1, status: 'ADMITTED' });
    const visit = data<Visit>(await client.get(`/api/visits/${a.visitId}`, doctor));
    expect(visit).toMatchObject({ status: 'IN_PROGRESS', assigneeId: 5, petId: 9002, appointmentId: null, serviceGroup: 'MEDICAL' });
    expect(visit.orderId).toBeGreaterThan(0);
    expect(data<Visit[]>(await client.get('/api/me/queue', doctor)).map((v) => v.visitId)).toContain(a.visitId);
    expect(data<{ status: string }>(await client.get('/api/boarding-bookings/2', await desk())).status).toBe('CHECKED_IN');
    expect(hasActiveAdmission(9002)).toBe(true);
  });

  it('thứ tự: body sai kiểu 400 (trước vai trò) → vai trò 403 → thiếu dịch vụ 400 → không đúng một nguồn 400 BR-LT-13 → 404 → 400 BR-LT-13 → 409', async () => {
    const customer = await as(ROLES.CUSTOMER);
    expect((await failure(client.post('/api/admissions', { visitId: 'abc', serviceId: 7101 }, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/admissions', undefined, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, customer))).data.errorCode).toBe('ACCESS_DENIED');

    const doctor = await vet();
    const missing = await failure(client.post('/api/admissions', { visitId: 2002 }, doctor));
    expect(missing).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('serviceId') } });
    expect((await failure(client.post('/api/admissions', { visitId: 0, serviceId: 7101 }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    // Cả hai hoặc không cái nào: BR-LT-13, đứng trước 404.
    for (const body of [{ serviceId: 7101 }, { visitId: 9999, boardingBookingId: 2, serviceId: 7101 }]) {
      expect(asRule(await failure(client.post('/api/admissions', body, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    expect((await failure(client.post('/api/admissions', { visitId: 9999, serviceId: 7101 }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions', { boardingBookingId: 9999, serviceId: 7101 }, doctor))).status).toBe(404);
    // Dịch vụ 404 đứng trước "lượt không IN_PROGRESS" (2102 đang chờ).
    expect((await failure(client.post('/api/admissions', { visitId: 2102, serviceId: 99999 }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 999 }, doctor))).status).toBe(404);
    for (const visitId of [2102, 2101]) {
      expect(asRule(await failure(client.post('/api/admissions', { visitId, serviceId: 7101 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    // Lượt IN_PROGRESS của VET khác (doctor2 không phụ trách 2002) → "không của mình" 400, không phải 403.
    const other = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, await as('doctor2@store1.vn')));
    expect(asRule(other, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    // 409 đứng sau mọi 400.
    await admit(doctor);
    const again = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, doctor));
    expect(again).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    expect(asRule(await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 1 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
  });

  it('dịch vụ không phải INPATIENT (khám, phẫu thuật), ngừng, chỉ bật ở chi nhánh khác; thú đã mất: 400 BR-LT-13 và không ghi gì', async () => {
    const doctor = await vet();
    for (const serviceId of [1, 7001, 7104, 7103]) {
      const res = await failure(client.post('/api/admissions', { visitId: 2002, serviceId }, doctor));
      expect([serviceId, ...asRule(res, 'BR-LT-13')]).toEqual([serviceId, ...BAD_RULE('BR-LT-13')]);
    }
    markPetDeceased(4);
    expect(asRule(await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    expect((await list(doctor)).totalElements).toBe(0);
  });

  it('đặt chỗ không CHECKED_IN (BOOKED #1, CHECKED_OUT #5): 400 BR-LT-13; thú đã nội trú: 409 trước khi tạo Visit điều trị', async () => {
    const doctor = await vet();
    for (const bookingId of [1, 5]) {
      expect(asRule(await failure(client.post('/api/admissions', { boardingBookingId: bookingId, serviceId: 7101 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    await admit(doctor, { boardingBookingId: 2, serviceId: 7101 });
    const visitsBefore = data<Visit[]>(await client.get('/api/visits', doctor)).length;
    expect((await failure(client.post('/api/admissions', { boardingBookingId: 2, serviceId: 7101 }, doctor))).status).toBe(409);
    expect(data<Visit[]>(await client.get('/api/visits', doctor))).toHaveLength(visitsBefore);
  });

  it('chuồng: không AVAILABLE (C-01 đang ở, C-04 bảo trì) 400; chuồng đã giữ cho ca khác 400 cho đến khi xuất viện; truyền đúng chuồng đang ở của đặt chỗ thì được', async () => {
    const doctor = await vet();
    for (const kennelId of [1, 4]) {
      expect(asRule(await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    const first = await admit(doctor, { visitId: 2002, serviceId: 7101, kennelId: 3 });
    const held = await failure(client.post('/api/admissions', { boardingBookingId: 3, serviceId: 7101, kennelId: 3 }, doctor));
    expect(held.data.message).toMatch(/\(BR-LT-13\)$/);
    expect((await discharge(doctor, first.admissionId)).status).toBe(200);
    expect((await admit(doctor, { boardingBookingId: 3, serviceId: 7101, kennelId: 2 })).kennelId).toBe(2);
    // Chuồng 3 trống lại sau khi ca đầu xuất viện.
    expect(data<Admission>(await client.get(`/api/admissions/${first.admissionId}`, doctor)).status).toBe('DISCHARGED');
  });
});

// ─── #2, #3 Xem ca ───

describe('xem ca nội trú (#2, #3)', () => {
  it('#2: mặc định chỉ ADMITTED của chi nhánh mình (rỗng lúc đầu); status=DISCHARGED ra lịch sử mẫu; ca chi nhánh 2 không lộ; nhập viện sớm nhất trước', async () => {
    const reception = await desk();
    expect(await list(reception)).toMatchObject({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const history = await list(reception, { status: 'DISCHARGED' });
    expect(history.content).toHaveLength(1);
    expect(history.content[0]).toMatchObject({ admissionId: 1, petName: 'Mochi', status: 'DISCHARGED', kennelCode: 'C-03' });
    expect(history.content[0].code).toMatch(/^NT-\d{6}-\d{4}$/);
    const a = await admit(await vet());
    const b = await admit(await vet(), { boardingBookingId: 3, serviceId: 7102 });
    expect((await list(reception)).content.map((s) => s.admissionId)).toEqual([a.admissionId, b.admissionId]);
    expect((await list(reception)).content.every((s) => s.admissionId !== 2)).toBe(true);
  });

  it('#2: status sai / page sai kiểu → 400 trước cả vai trò; phân trang kẹp page / size', async () => {
    const customer = await as(ROLES.CUSTOMER);
    expect((await failure(client.get('/api/admissions', { params: { status: 'GONE' }, ...customer }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/admissions', { params: { size: 'x' }, ...customer }))).data.errorCode).toBe('MALFORMED_REQUEST');
    await admit(await vet());
    await admit(await vet(), { boardingBookingId: 3, serviceId: 7102 });
    const nurseAuth = await nurse();
    expect(await list(nurseAuth, { size: 1, page: 1 })).toMatchObject({ page: 1, size: 1, totalElements: 2, totalPages: 2 });
    expect(await list(nurseAuth, { page: -2, size: 0 })).toMatchObject({ page: 0, size: 1 });
    expect((await list(nurseAuth, { size: 999 })).size).toBe(100);
  });

  it('#3: chi tiết lịch sử mẫu; 404; chi nhánh khác 403 phạm vi (không mã rule); path sai kiểu 400', async () => {
    const doctor = await vet();
    expect(data<Admission>(await client.get('/api/admissions/1', doctor))).toMatchObject({
      status: 'DISCHARGED',
      dischargeOutcome: 'HOME',
      kennelId: 3,
      serviceName: 'Nội trú điều trị (theo ngày)',
      unitPrice: 350_000,
      orderLineId: 5002,
      visitId: 61,
      petName: 'Mochi',
    });
    expect(data<Admission>(await client.get('/api/admissions/1', doctor)).dischargeSummary).toContain('tái khám');
    expect((await failure(client.get('/api/admissions/999', doctor))).status).toBe(404);
    const other = await failure(client.get('/api/admissions/2', doctor));
    expect(other).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
    expect(other.data.message).not.toMatch(/\(BR-/);
    expect((await failure(client.get('/api/admissions/abc', doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

// ─── #4 Xuất viện ───

describe('xuất viện (#4, BR-LT-14)', () => {
  it('flow: còn y lệnh ORDERED thì 400 BR-LT-14; xong hết thì xuất viện, ghi tóm tắt đã cắt khoảng trắng, dòng dịch vụ vào Order, ca không còn ADMITTED', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o = await order(doctor, a.admissionId);
    expect(asRule(await failure(discharge(doctor, a.admissionId)), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    await complete(doctor, o.admissionOrderId);
    const res = await discharge(doctor, a.admissionId, { outcome: 'HOME', summary: '  Ổn định, về nhà  ' });
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ status: 'DISCHARGED', dischargeOutcome: 'HOME', dischargeSummary: 'Ổn định, về nhà', unitPrice: 350_000 });
    expect(Number.isInteger(res.data.data.orderLineId)).toBe(true);
    expect(Date.parse(res.data.data.dischargedAt)).not.toBeNaN();
    expect(hasActiveAdmission(4)).toBe(false);
    expect((await list(doctor)).totalElements).toBe(0);
  });

  it('số ngày tính theo ngày VN từ ngày nhập tới ngày xuất, tối thiểu 1; giá là giá đã chốt lúc nhập viện', async () => {
    const calls: unknown[][] = [];
    registerVisitOrderSource({
      addServiceLine: (...args) => (calls.push(args), 7000 + calls.length),
    });
    const doctor = await vet();
    const a = await admit(doctor);
    expect((await discharge(doctor, a.admissionId)).status).toBe(200);
    expect(calls[0]).toEqual([2002, 7101, 1, 5, 350_000]);
    // Nhập 3 ngày trước, rồi giá dịch vụ đổi: vẫn 350.000 × 3.
    const b = await admit(doctor);
    patchState((s) => {
      const row = s.admissions.find((x) => x.admissionId === b.admissionId)!;
      row.admittedAt = new Date(Date.now() - 3 * 86_400_000).toISOString();
      row.unitPrice = 400_000;
    });
    const res = await discharge(doctor, b.admissionId);
    expect(res.data.data.orderLineId).toBe(7002);
    expect(calls[1]).toEqual([2002, 7101, 3, 5, 400_000]);
  });

  it('thứ tự: path / body sai kiểu 400 → vai trò 403 → thiếu kết cục 400 → 404 → chi nhánh 403 → BR-LT-14 → 409', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/discharge`;
    expect((await failure(client.post('/api/admissions/abc/discharge', {}, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { outcome: 'DEAD', summary: 'x' }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { outcome: 'HOME', summary: [] }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { outcome: 'HOME', summary: 'x' }, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    expect(await failure(client.post(url, { summary: 'x' }, doctor))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('outcome') } });
    expect((await failure(client.post('/api/admissions/999/discharge', { summary: 'x' }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admissions/999/discharge', { outcome: 'HOME', summary: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/discharge', { outcome: 'HOME', summary: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Thiếu tóm tắt: BR-LT-14 (không phải VALIDATION_FAILED), đứng trước kiểm y lệnh ORDERED và trước 409 (ca #1 đã DISCHARGED).
    await order(doctor, a.admissionId);
    for (const summary of [undefined, '', '   ']) {
      expect(asRule(await failure(client.post(url, { outcome: 'HOME', summary }, doctor)), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    }
    expect((await failure(client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: '' }, doctor))).status).toBe(400);
    expect((await failure(client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: 'Xong' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('kết cục DECEASED: thú chưa đánh dấu đã mất → 400 BR-LT-14 (hợp đồng tự mâu thuẫn, xem A7); đã đánh dấu thì xuất viện được', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    expect(asRule(await failure(discharge(doctor, a.admissionId, { outcome: 'DECEASED', summary: 'Thú mất lúc 10 giờ' })), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    expect(data<Admission>(await client.get(`/api/admissions/${a.admissionId}`, doctor)).status).toBe('ADMITTED');
    markPetDeceased(4);
    const done = await discharge(doctor, a.admissionId, { outcome: 'DECEASED', summary: 'Thú mất lúc 10 giờ' });
    expect(done.data.data).toMatchObject({ status: 'DISCHARGED', dischargeOutcome: 'DECEASED' });
  });
});

// ─── #5, #6 Y lệnh ───

describe('y lệnh nội trú (#5, #6, BR-LT-15)', () => {
  it('ra y lệnh mọi loại: 201, ORDERED, nội dung cắt khoảng trắng, giờ chuẩn hóa ISO; prescriptionItemId của loại khác bị bỏ', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const at = '2026-11-07T09:30:00+07:00';
    const res = await client.post(`/api/admissions/${a.admissionId}/orders`, { orderType: 'PROCEDURE', content: '  Truyền dịch  ', scheduledAt: at, prescriptionItemId: 99 }, doctor);
    expect(res.status).toBe(201);
    expect(res.data.data).toEqual({
      admissionOrderId: expect.any(Number),
      admissionId: a.admissionId,
      orderType: 'PROCEDURE',
      content: 'Truyền dịch',
      scheduledAt: new Date(at).toISOString(),
      status: 'ORDERED',
      prescriptionItemId: null,
      doneAt: null,
      doneNote: null,
      cancelReason: null,
    });
    for (const orderType of ['FEEDING', 'MONITORING', 'OTHER']) expect((await order(doctor, a.admissionId, { orderType, content: 'x', scheduledAt: at })).orderType).toBe(orderType);
  });

  it('y lệnh MEDICATION: phải liên kết dòng đơn thuốc đúng Visit của ca (400 BR-LT-15 nếu thiếu hoặc của lượt khác)', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const at = SOON();
    const url = `/api/admissions/${a.admissionId}/orders`;
    expect(asRule(await failure(client.post(url, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: at }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    const seeded = rawState().orders; // dòng đơn thuốc của lượt 61 không thuộc lượt 2002
    expect(seeded.length).toBeGreaterThan(0);
    expect(asRule(await failure(client.post(url, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: at, prescriptionItemId: 2 }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    const item = data<{ itemId: number }>(await client.post('/api/visits/2002/prescriptions', { productId: 3, quantity: 2, dosageInstructions: 'Ngày 2 lần' }, doctor));
    const med = await order(doctor, a.admissionId, { orderType: 'MEDICATION', content: 'Amoxicillin 250mg', scheduledAt: at, prescriptionItemId: item.itemId });
    expect(med).toMatchObject({ orderType: 'MEDICATION', prescriptionItemId: item.itemId });
  });

  it('thứ tự: body sai kiểu 400 → VET → thiếu / dài 400 VALIDATION_FAILED → 404 → chi nhánh 403 → BR-LT-15 → 409', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/orders`;
    const body = { orderType: 'FEEDING', content: 'Cho ăn', scheduledAt: SOON() };
    const customer = await as(ROLES.CUSTOMER);
    for (const bad of [{ ...body, orderType: 'BATH' }, { ...body, scheduledAt: 'mai' }, { ...body, scheduledAt: '2026-11-07' }, { ...body, prescriptionItemId: 'x' }, { ...body, content: [] }]) {
      expect((await failure(client.post(url, bad, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(client.post(url, body, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    for (const [patch, field] of [[{ orderType: undefined }, 'orderType'], [{ content: ' ' }, 'content'], [{ content: 'x'.repeat(501) }, 'content'], [{ scheduledAt: undefined }, 'scheduledAt'], [{ prescriptionItemId: 0 }, 'prescriptionItemId']] as const) {
      const res = await failure(client.post(url, { ...body, ...patch }, doctor));
      expect([res.status, res.data.errorCode, res.data.message.includes(field)]).toEqual([400, 'VALIDATION_FAILED', true]);
    }
    expect((await failure(client.post('/api/admissions/999/orders', { ...body, content: '' }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admissions/999/orders', body, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/orders', body, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // BR-LT-15 (MEDICATION thiếu dòng đơn) đứng trước 409 (ca #1 đã DISCHARGED); 409 khi hợp lệ.
    expect((await failure(client.post('/api/admissions/1/orders', { ...body, orderType: 'MEDICATION' }, doctor))).status).toBe(400);
    expect(await failure(client.post('/api/admissions/1/orders', body, doctor))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
  });

  it('#6: mảng không phân trang, scheduledAt tăng dần (trùng thì mã tăng dần), lọc status; CARETAKER và lễ tân xem được', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const late = await order(doctor, a.admissionId, { orderType: 'FEEDING', content: 'Bữa tối', scheduledAt: '2026-11-07T18:00:00+07:00' });
    const early1 = await order(doctor, a.admissionId, { orderType: 'FEEDING', content: 'Bữa sáng 1', scheduledAt: '2026-11-07T07:00:00+07:00' });
    const early2 = await order(doctor, a.admissionId, { orderType: 'OTHER', content: 'Bữa sáng 2', scheduledAt: '2026-11-07T07:00:00+07:00' });
    await complete(doctor, early1.admissionOrderId);
    const all = data<AdmissionOrder[]>(await client.get(`/api/admissions/${a.admissionId}/orders`, await nurse()));
    expect(all.map((o) => o.admissionOrderId)).toEqual([early1.admissionOrderId, early2.admissionOrderId, late.admissionOrderId]);
    const done = data<AdmissionOrder[]>(await client.get(`/api/admissions/${a.admissionId}/orders`, { params: { status: 'DONE' }, ...(await desk()) }));
    expect(done.map((o) => o.admissionOrderId)).toEqual([early1.admissionOrderId]);
    expect((await failure(client.get(`/api/admissions/${a.admissionId}/orders`, { params: { status: 'OLD' }, ...doctor }))).data.errorCode).toBe('MALFORMED_REQUEST');
    // Lịch sử mẫu của ca #1: có đủ trạng thái DONE và CANCELLED.
    const history = data<AdmissionOrder[]>(await client.get('/api/admissions/1/orders', doctor));
    expect(new Set(history.map((o) => o.status))).toEqual(new Set(['DONE', 'CANCELLED']));
    expect(history.find((o) => o.status === 'CANCELLED')?.cancelReason).toContain('ổn định');
    expect((await failure(client.get('/api/admissions/2/orders', doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/admissions/999/orders', doctor))).status).toBe(404);
  });
});

// ─── #7, #8 Thực hiện / hủy y lệnh ───

describe('thực hiện và hủy y lệnh (#7, #8, BR-LT-15)', () => {
  it('VET và CARETAKER thực hiện việc không phải thuốc: ORDERED → DONE, ghi người làm và ghi chú; hủy kèm lý do', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o1 = await order(doctor, a.admissionId);
    const o2 = await order(doctor, a.admissionId);
    const done = await complete(await nurse(), o1.admissionOrderId, '  Thú ăn hết  ');
    expect(done).toMatchObject({ status: 'DONE', doneNote: 'Thú ăn hết' });
    expect(Date.parse(done.doneAt!)).not.toBeNaN();
    const cancelled = data<AdmissionOrder>(await client.post(`/api/admission-orders/${o2.admissionOrderId}/cancel`, { reason: ' Thú đã no ' }, doctor));
    expect(cancelled).toMatchObject({ status: 'CANCELLED', cancelReason: 'Thú đã no', doneAt: null });
  });

  it('CARETAKER không thực hiện được y lệnh thuốc: 403 ACCESS_DENIED, y lệnh vẫn ORDERED; VET thực hiện được', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const item = data<{ itemId: number }>(await client.post('/api/visits/2002/prescriptions', { productId: 3, quantity: 2, dosageInstructions: 'Ngày 2 lần' }, doctor));
    const med = await order(doctor, a.admissionId, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: SOON(), prescriptionItemId: item.itemId });
    const denied = await failure(client.post(`/api/admission-orders/${med.admissionOrderId}/complete`, { note: 'Đã cho uống' }, await nurse()));
    expect(denied).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED' } });
    expect(data<AdmissionOrder[]>(await client.get(`/api/admissions/${a.admissionId}/orders`, doctor))[0].status).toBe('ORDERED');
    expect((await complete(doctor, med.admissionOrderId, 'Đã cho uống')).status).toBe('DONE');
  });

  it('thứ tự #7: path / body sai kiểu 400 → vai trò → ghi chú > 300 400 → 404 → chi nhánh 403 → thiếu ghi chú 400 BR-LT-15 → 409', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o = await order(doctor, a.admissionId);
    const url = `/api/admission-orders/${o.admissionOrderId}/complete`;
    expect((await failure(client.post('/api/admission-orders/abc/complete', { note: 'x' }, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { note: [] }, await desk()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { note: 'x' }, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post(url, { note: 'x'.repeat(301) }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admission-orders/999/complete', { note: 'x' }, doctor))).status).toBe(404);
    const branch2 = rawState().orders.find((x) => x.admissionId === 2)!.admissionOrderId;
    expect((await failure(client.post(`/api/admission-orders/${branch2}/complete`, { note: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    for (const body of [{}, { note: '' }, { note: '   ' }, { note: null }]) {
      expect(asRule(await failure(client.post(url, body, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    }
    // Y lệnh đã DONE (mẫu #1) hoặc đã CANCELLED: 409.
    expect((await failure(client.post('/api/admission-orders/1/complete', { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/admission-orders/3/complete', { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await complete(doctor, o.admissionOrderId);
    expect((await failure(client.post(url, { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('thứ tự #8: body sai kiểu 400 → VET → lý do > 300 400 → 404 → chi nhánh 403 → thiếu lý do 400 BR-LT-15 → 409', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o = await order(doctor, a.admissionId);
    const url = `/api/admission-orders/${o.admissionOrderId}/cancel`;
    expect((await failure(client.post(url, { reason: {} }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { reason: 'x' }, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post(url, { reason: 'x'.repeat(301) }, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admission-orders/999/cancel', { reason: 'x' }, doctor))).status).toBe(404);
    const branch2 = rawState().orders.find((x) => x.admissionId === 2)!.admissionOrderId;
    expect((await failure(client.post(`/api/admission-orders/${branch2}/cancel`, { reason: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    for (const body of [{}, { reason: '' }, { reason: ' ' }, { reason: null }]) {
      expect(asRule(await failure(client.post(url, body, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    }
    expect((await failure(client.post('/api/admission-orders/1/cancel', { reason: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await client.post(url, { reason: 'x' }, doctor);
    expect((await failure(client.post(url, { reason: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── #9, #10 Theo dõi ───

const vitals = { temperatureC: 38.6, heartRate: 140, respiratoryRate: 30, weightKg: 18.4, appetite: 'REDUCED', note: ' Thú lờ đờ ', abnormal: false };

describe('chỉ số theo dõi (#9, #10, BR-LT-16)', () => {
  it('VET và CARETAKER ghi: 201, đủ trường, tên người ghi; cân nặng vào hồ sơ cân của thú (BR-KH-04)', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const res = await client.post(`/api/admissions/${a.admissionId}/observations`, vitals, await nurse());
    expect(res.status).toBe(201);
    expect(res.data.data).toEqual({
      observationId: expect.any(Number),
      admissionId: a.admissionId,
      recordedByName: 'Thu Hà',
      temperatureC: 38.6,
      heartRate: 140,
      respiratoryRate: 30,
      weightKg: 18.4,
      appetite: 'REDUCED',
      note: 'Thú lờ đờ',
      abnormal: false,
      createdAt: expect.any(String),
    });
    expect(petCurrentWeight(4)).toBe(18.4);
    const note = await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, note: 'Thú ngủ yên' }, doctor);
    expect(note.data.data).toMatchObject({ recordedByName: 'BS. Minh Anh', temperatureC: null, weightKg: null, appetite: null, note: 'Thú ngủ yên' });
    expect(petCurrentWeight(4)).toBe(18.4);
  });

  it('abnormal = true: báo VET phụ trách bằng thông báo CARE_LOG_ABNORMAL khi người ghi là CARETAKER; VET tự ghi thì không tự báo mình', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const unread = async () =>
      data<PageResponse<{ type: string; title: string }>>(await client.get('/api/me/notifications', { params: { unreadOnly: true }, ...doctor })).content.filter((n) => n.type === 'CARE_LOG_ABNORMAL');
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: true, note: 'Tự ghi' }, doctor);
    expect(await unread()).toHaveLength(0);
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: true, temperatureC: 40.1 }, await nurse());
    expect(await unread()).toMatchObject([{ title: 'Theo dõi nội trú bất thường: Lu' }]);
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, temperatureC: 38.5 }, await nurse());
    expect(await unread()).toHaveLength(1);
  });

  it('thứ tự: body sai kiểu 400 → vai trò → thiếu abnormal / ghi chú > 500 400 → 404 → chi nhánh 403 → BR-LT-16 → 409', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/observations`;
    const customer = await as(ROLES.CUSTOMER);
    for (const bad of [{ ...vitals, temperatureC: 'nóng' }, { ...vitals, heartRate: 80.5 }, { ...vitals, respiratoryRate: '30' }, { ...vitals, appetite: 'MAYBE' }, { ...vitals, note: [] }, { ...vitals, abnormal: 'có' }]) {
      expect((await failure(client.post(url, bad, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(client.post(url, vitals, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    const noFlag = { ...vitals, abnormal: undefined };
    expect(await failure(client.post(url, noFlag, doctor))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('abnormal') } });
    expect((await failure(client.post(url, { ...vitals, note: 'x'.repeat(501) }, doctor))).data.message).toContain('note');
    expect((await failure(client.post('/api/admissions/999/observations', noFlag, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admissions/999/observations', vitals, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/observations', vitals, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // BR-LT-16 đứng trước 409 (ca #1 đã DISCHARGED); hợp lệ thì 409.
    expect((await failure(client.post('/api/admissions/1/observations', { abnormal: true }, doctor))).status).toBe(400);
    expect(await failure(client.post('/api/admissions/1/observations', vitals, doctor))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
  });

  it('BR-LT-16: không có chỉ số hay ghi chú nào (abnormal một mình không đủ), giá trị ngoài khoảng: 400 và không ghi gì', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/observations`;
    for (const body of [{ abnormal: false }, { abnormal: true }, { abnormal: false, note: '   ' }, { abnormal: false, temperatureC: null, note: null }]) {
      expect(asRule(await failure(client.post(url, body, doctor)), 'BR-LT-16')).toEqual(BAD_RULE('BR-LT-16'));
    }
    for (const patch of [{ temperatureC: 0 }, { temperatureC: 1000 }, { heartRate: 0 }, { heartRate: 40000 }, { respiratoryRate: -3 }, { weightKg: 0 }, { weightKg: 10000 }]) {
      const res = await failure(client.post(url, { abnormal: false, ...patch }, doctor));
      expect([JSON.stringify(patch), ...asRule(res, 'BR-LT-16')]).toEqual([JSON.stringify(patch), ...BAD_RULE('BR-LT-16')]);
    }
    expect(data<PageResponse<Observation>>(await client.get(url, doctor)).totalElements).toBe(0);
    // Biên hợp lệ.
    expect((await client.post(url, { abnormal: false, temperatureC: 999.9, heartRate: 32767, respiratoryRate: 1, weightKg: 0.01 }, doctor)).status).toBe(201);
  });

  it('#10: mới nhất trước (trùng giờ thì mã lớn trước), phân trang; lịch sử mẫu có 3 dòng; lễ tân xem được; chi nhánh khác 403, không có 404', async () => {
    const seeded = data<PageResponse<Observation>>(await client.get('/api/admissions/1/observations', await desk()));
    expect(seeded.totalElements).toBe(3);
    const times = seeded.content.map((o) => Date.parse(o.createdAt));
    expect(times).toEqual([...times].sort((x, y) => y - x));
    expect(seeded.content.filter((o) => o.abnormal)).toHaveLength(1);
    expect(seeded.content.map((o) => o.recordedByName)).toEqual(['BS. Minh Anh', 'Thu Hà', 'Thu Hà']);

    const doctor = await vet();
    const a = await admit(doctor);
    const first = data<Observation>(await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, note: 'Lần 1' }, doctor));
    const second = data<Observation>(await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, note: 'Lần 2' }, doctor));
    const page = data<PageResponse<Observation>>(await client.get(`/api/admissions/${a.admissionId}/observations`, { params: { size: 1 }, ...doctor }));
    expect(page).toMatchObject({ size: 1, totalElements: 2, totalPages: 2 });
    expect(page.content[0].observationId).toBe(second.observationId);
    expect(first.observationId).toBeLessThan(second.observationId);
    expect((await failure(client.get('/api/admissions/2/observations', doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/admissions/999/observations', doctor))).status).toBe(404);
    expect((await failure(client.get('/api/admissions/abc/observations', doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/admissions/1/observations', { params: { page: 'x' }, ...doctor }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

// ─── Hoàn tất lượt (visit-v1 #10) bị chặn bởi ca ADMITTED (BR-LT-14) ───

describe('guard hoàn tất lượt khi còn ca nội trú ADMITTED (BR-LT-14)', () => {
  it('còn ADMITTED: 400 BR-LT-14; xuất viện xong thì hoàn tất được', async () => {
    const doctor = await vet();
    await client.put('/api/visits/2002/medical-record', { diagnosis: 'Khỏe' }, doctor);
    const a = await admit(doctor);
    const blocked = await failure(client.post('/api/visits/2002/complete', undefined, doctor));
    expect(asRule(blocked, 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    await discharge(doctor, a.admissionId);
    expect(data<Visit>(await client.post('/api/visits/2002/complete', undefined, doctor))).toMatchObject({ visitId: 2002, status: 'COMPLETED' });
  });
});

// ─── State lưu dạng cũ / thiếu trường ───

describe('state localStorage', () => {
  it('state rỗng {} (thiếu mọi bảng) không làm route nổ: gộp dữ liệu mẫu, ghi lại, mã mới không đè mã mẫu', async () => {
    localStorage.setItem('petcare-mock-inpatient', '{}');
    const doctor = await vet();
    expect((await list(doctor, { status: 'DISCHARGED' })).content.map((s) => s.admissionId)).toEqual([1]);
    expect(Object.keys(JSON.parse(localStorage.getItem('petcare-mock-inpatient')!)).sort()).toEqual(['admissions', 'counters', 'observations', 'orders', 'seq']);
    expect((await admit(doctor)).admissionId).toBe(3);
  });

  it('state chỉ có admissions (hàng thiếu trường): trường tùy chọn là null, các bảng khác lấy mặc định, mã mới tiếp theo mã lớn nhất', async () => {
    localStorage.setItem(
      'petcare-mock-inpatient',
      JSON.stringify({ admissions: [{ admissionId: 10, code: 'NT-261001-0001', visitId: 61, petId: 1, customerId: 1, branchId: 1, serviceId: 7101, unitPrice: 350000, status: 'ADMITTED', admittedBy: 5, admittedAt: '2026-10-01T03:00:00.000Z' }] }),
    );
    const doctor = await vet();
    expect(data<Admission>(await client.get('/api/admissions/10', doctor))).toMatchObject({ boardingBookingId: null, kennelId: null, dischargeOutcome: null, orderLineId: null, petName: 'Mochi' });
    expect(hasActiveAdmission(1)).toBe(true);
    expect((await order(doctor, 10)).admissionOrderId).toBeGreaterThan(0);
    expect((await admit(doctor, { boardingBookingId: 3, serviceId: 7101 })).admissionId).toBe(11);
  });

  it('JSON hỏng: dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-inpatient', 'không phải json');
    expect((await list(await vet(), { status: 'DISCHARGED' })).totalElements).toBe(1);
  });
});
