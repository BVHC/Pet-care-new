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

// T79: mock khớp BE thật (đo 25/11/2026 trên BE 8083, bản sao petcare_t79; số đo ở docs/testcases/T57-fe.md mục NT và final2/ket-qua-t79-noi-tru-24-11.md). Thứ tự lỗi của BE: 400 hình thức (sai kiểu, VALIDATION_FAILED)
// → 403 vai trò → … → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 BR-LT-*; riêng #4 tóm tắt rỗng đứng ngay sau vai trò.
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

const admit = async (auth: Auth, body: object = { visitId: 2002, serviceId: 7101, kennelId: 3 }) => data<Admission>(await client.post('/api/admissions', body, auth));
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
  { name: '#1 nhập viện', call: (a) => client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, a), allowed: ['VET'] },
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

/** Đọc / sửa state thô của mock lưu trú (đặt chỗ): đổi trạng thái hoặc thú của một đặt chỗ mẫu. */
const patchBoarding = (fn: (s: { bookings: Record<string, unknown>[] }) => void) => {
  const s = JSON.parse(localStorage.getItem('petcare-mock-boarding')!) as { bookings: Record<string, unknown>[] };
  fn(s);
  localStorage.setItem('petcare-mock-boarding', JSON.stringify(s));
};
/** Lượt IN_PROGRESS thứ hai của BS. Minh Anh (#5): lễ tân tiếp nhận cấp cứu, gán, VET gọi. Mochi (thú 1) không còn lượt mở. */
async function secondVisit(): Promise<number> {
  const reception = await desk();
  const doctor = await vet();
  const created = data<Visit>(await client.post('/api/visits', { petId: 1, serviceId: 1, isEmergency: true }, reception));
  await client.post(`/api/visits/${created.visitId}/assign`, { assigneeId: 5 }, reception);
  await client.post(`/api/visits/${created.visitId}/call`, undefined, doctor);
  return created.visitId;
}

describe('nhập viện (#1, BR-LT-13, 17)', () => {
  it('từ lượt đang khám của mình kèm chuồng điều trị: 201, ADMITTED, giá chốt theo ngày, mã NT-yyMM-xxxxx, tạm tính 1 ngày', async () => {
    const res = await client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, await vet());
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã nhập viện', code: 201 });
    expect(res.data.data).toMatchObject({
      visitId: 2002,
      petId: 4,
      petName: 'Lu',
      branchId: 1,
      boardingBookingId: null,
      kennelId: 3,
      serviceId: 7101,
      serviceName: 'Nội trú điều trị (theo ngày)',
      unitPrice: 350_000,
      status: 'ADMITTED',
      billedDays: 1,
      estimatedAmount: 350_000,
      dischargeOutcome: null,
      dischargeSummary: null,
      dischargedAt: null,
      orderLineId: null,
    });
    expect(res.data.data.code).toMatch(/^NT-\d{4}-\d{5}$/);
    expect(Date.parse(res.data.data.admittedAt)).not.toBeNaN();
    expect(hasActiveAdmission(4)).toBe(true);
    expect(hasActiveAdmission(1)).toBe(false);
  });

  it('mã tăng dần trong tháng; ca mới hiện ở danh sách ADMITTED của lễ tân kèm mã chuồng và tạm tính', async () => {
    const a = await admit(await vet(), { visitId: 2002, serviceId: 7101, kennelId: 3 });
    expect(a.kennelId).toBe(3);
    const b = await admit(await vet(), { boardingBookingId: 2, serviceId: 7102 });
    expect(Number(b.code.slice(-5))).toBe(Number(a.code.slice(-5)) + 1);
    expect(b.code.slice(0, 7)).toBe(a.code.slice(0, 7));
    const page = await list(await desk());
    expect(page.content.map((s) => [s.admissionId, s.petName, s.kennelCode, s.billedDays, s.estimatedAmount])).toEqual([
      [a.admissionId, 'Lu', 'C-03', 1, 350_000],
      [b.admissionId, 'Cốm', 'C-01', 1, 600_000],
    ]);
  });

  it('từ đặt chỗ lưu trú CHECKED_IN: tạo Visit điều trị IN_PROGRESS gán VET gọi, giữ chuồng đang ở (không có kennelId), đặt chỗ vẫn CHECKED_IN (BR-LT-17)', async () => {
    const doctor = await vet();
    const a = await admit(doctor, { boardingBookingId: 2, serviceId: 7101 });
    expect(a).toMatchObject({ boardingBookingId: 2, petId: 9002, petName: 'Cốm', kennelId: null, status: 'ADMITTED' });
    const visit = data<Visit>(await client.get(`/api/visits/${a.visitId}`, doctor));
    expect(visit).toMatchObject({ status: 'IN_PROGRESS', assigneeId: 5, petId: 9002, appointmentId: null, serviceGroup: 'MEDICAL' });
    expect(visit.orderId).toBeGreaterThan(0);
    expect(data<Visit[]>(await client.get('/api/me/queue', doctor)).map((v) => v.visitId)).toContain(a.visitId);
    expect(data<{ status: string }>(await client.get('/api/boarding-bookings/2', await desk())).status).toBe('CHECKED_IN');
    expect(hasActiveAdmission(9002)).toBe(true);
  });

  it('đặt chỗ OVERDUE (quá hạn trả, thú vẫn trong chuồng) cũng nhập viện được như CHECKED_IN', async () => {
    await vet(); // nạp state lưu trú
    await client.get('/api/boarding-bookings/2', await desk());
    patchBoarding((s) => {
      s.bookings.find((b) => b.bookingId === 2)!.status = 'OVERDUE';
    });
    const a = await admit(await vet(), { boardingBookingId: 2, serviceId: 7101 });
    expect(a).toMatchObject({ boardingBookingId: 2, petName: 'Cốm', status: 'ADMITTED' });
  });

  it('thứ tự: sai kiểu 400 → VALIDATION_FAILED 400 (cả hai trước vai trò) → vai trò 403 → nguồn / chuồng BR-LT-13 → 404 → 409 → điều kiện BR-LT-13 → dịch vụ → chuồng', async () => {
    const customer = await as(ROLES.CUSTOMER);
    expect((await failure(client.post('/api/admissions', { visitId: 'abc', serviceId: 7101 }, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/admissions', undefined, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    // BE đo: thiếu serviceId + lễ tân → VALIDATION_FAILED, không phải 403 (Bean Validation chạy trước @PreAuthorize).
    expect(await failure(client.post('/api/admissions', { visitId: 2002, kennelId: 3 }, await desk()))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED' } });
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, customer))).data.errorCode).toBe('ACCESS_DENIED');

    const doctor = await vet();
    const missing = await failure(client.post('/api/admissions', { visitId: 2002, kennelId: 3 }, doctor));
    expect(missing).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('serviceId') } });
    for (const body of [{ visitId: 0, serviceId: 7101, kennelId: 3 }, { visitId: 2002, serviceId: 0, kennelId: 3 }, { visitId: 2002, serviceId: 7101, kennelId: -1 }]) {
      expect((await failure(client.post('/api/admissions', body, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    }
    // Không đúng một nguồn, nhập từ khám thiếu chuồng, nhập từ lưu trú có chuồng: BR-LT-13, đứng trước 404.
    for (const body of [{ serviceId: 7101, kennelId: 3 }, { visitId: 9999, boardingBookingId: 2, serviceId: 7101 }, { visitId: 9999, serviceId: 7101 }, { boardingBookingId: 9999, serviceId: 7101, kennelId: 3 }]) {
      expect(asRule(await failure(client.post('/api/admissions', body, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    expect((await failure(client.post('/api/admissions', { visitId: 9999, serviceId: 7101, kennelId: 3 }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions', { boardingBookingId: 9999, serviceId: 7101 }, doctor))).status).toBe(404);
    // Điều kiện của lượt (2102 đang chờ, 2101 …) đứng TRƯỚC dịch vụ 404; dịch vụ 404 đứng trước chuồng 404.
    for (const visitId of [2102, 2101]) {
      expect(asRule(await failure(client.post('/api/admissions', { visitId, serviceId: 99999, kennelId: 3 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 99999, kennelId: 999 }, doctor))).data.message).toContain('dịch vụ');
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 999 }, doctor))).data.message).toContain('chuồng');
    // Lượt IN_PROGRESS của VET khác (doctor2 không phụ trách 2002) → "không của mình" 400, không phải 403.
    const other = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, await as('doctor2@store1.vn')));
    expect(asRule(other, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    // 409 đứng TRƯỚC mọi 400 điều kiện (kể cả dịch vụ sai): BE đo ca trùng thú trả 409 dù serviceId không phải nội trú.
    await admit(doctor);
    const again = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 4 }, doctor));
    expect(again).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    expect((await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 1, kennelId: 4 }, doctor))).status).toBe(409);
  });

  it('dịch vụ không phải INPATIENT (khám), ngừng, chỉ bật ở chi nhánh khác; thú đã mất: 400 BR-LT-13 đúng câu của BE và không ghi gì', async () => {
    const doctor = await vet();
    const messages: Record<number, string> = { 1: 'không phải dịch vụ nội trú', 7104: 'ngừng kinh doanh', 7103: 'chưa được bật tại chi nhánh' };
    for (const serviceId of [1, 7104, 7103]) {
      const res = await failure(client.post('/api/admissions', { visitId: 2002, serviceId, kennelId: 3 }, doctor));
      expect([serviceId, ...asRule(res, 'BR-LT-13')]).toEqual([serviceId, ...BAD_RULE('BR-LT-13')]);
      expect(res.data.message).toContain(messages[serviceId]);
    }
    markPetDeceased(4);
    const dead = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, doctor));
    expect(asRule(dead, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    expect(dead.data.message).toContain('đã mất');
    expect((await list(doctor)).totalElements).toBe(0);
  });

  it('thú đang lưu trú mà nhập từ lượt khám: 400 BR-LT-13 (phải đi đường đặt chỗ, không cấp thêm chuồng)', async () => {
    const doctor = await vet();
    await client.get('/api/boarding-bookings/3', await desk()); // nạp state lưu trú
    patchBoarding((s) => {
      s.bookings.find((b) => b.bookingId === 3)!.petId = 4; // đặt chỗ #3 (Mực) đổi sang Lu: Lu đang nằm trong chuồng
    });
    const res = await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId: 3 }, doctor));
    expect(asRule(res, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    expect(res.data.message).toContain('đang lưu trú');
    expect((await list(doctor)).totalElements).toBe(0);
  });

  it('đặt chỗ không CHECKED_IN / OVERDUE (BOOKED #1, CHECKED_OUT #5): 400 BR-LT-13; thú đã nội trú: 409 trước khi tạo Visit điều trị', async () => {
    const doctor = await vet();
    for (const bookingId of [1, 5]) {
      expect(asRule(await failure(client.post('/api/admissions', { boardingBookingId: bookingId, serviceId: 7101 }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    await admit(doctor, { boardingBookingId: 2, serviceId: 7101 });
    const visitsBefore = data<Visit[]>(await client.get('/api/visits', doctor)).length;
    expect((await failure(client.post('/api/admissions', { boardingBookingId: 2, serviceId: 7101 }, doctor))).status).toBe(409);
    expect(data<Visit[]>(await client.get('/api/visits', doctor))).toHaveLength(visitsBefore);
  });

  it('chuồng điều trị: không AVAILABLE (C-01 đang ở, C-04 bảo trì) 400; đã giữ cho ca khác 400 cho tới khi xuất viện; nhập từ lưu trú mà gửi kennelId 400', async () => {
    const doctor = await vet();
    for (const kennelId of [1, 4]) {
      expect(asRule(await failure(client.post('/api/admissions', { visitId: 2002, serviceId: 7101, kennelId }, doctor)), 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    }
    const first = await admit(doctor, { visitId: 2002, serviceId: 7101, kennelId: 3 });
    const visitId = await secondVisit();
    const held = await failure(client.post('/api/admissions', { visitId, serviceId: 7101, kennelId: 3 }, doctor));
    expect(asRule(held, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
    expect(held.data.message).toContain('không còn trống');
    expect((await discharge(doctor, first.admissionId)).status).toBe(200);
    expect((await admit(doctor, { visitId, serviceId: 7101, kennelId: 3 })).kennelId).toBe(3);
    expect(data<Admission>(await client.get(`/api/admissions/${first.admissionId}`, doctor)).status).toBe('DISCHARGED');
    const noKennel = await failure(client.post('/api/admissions', { boardingBookingId: 3, serviceId: 7101, kennelId: 2 }, doctor));
    expect(asRule(noKennel, 'BR-LT-13')).toEqual(BAD_RULE('BR-LT-13'));
  });
});

// ─── #2, #3 Xem ca ───

describe('xem ca nội trú (#2, #3)', () => {
  it('#2: mặc định chỉ ADMITTED của chi nhánh mình (rỗng lúc đầu); status=DISCHARGED ra lịch sử mẫu; ca chi nhánh 2 không lộ; nhập viện sớm nhất trước', async () => {
    const reception = await desk();
    expect(await list(reception)).toMatchObject({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const history = await list(reception, { status: 'DISCHARGED' });
    expect(history.content).toHaveLength(1);
    expect(history.content[0]).toMatchObject({ admissionId: 1, petName: 'Mochi', status: 'DISCHARGED', kennelCode: 'C-03', billedDays: 1, estimatedAmount: 350_000 });
    expect(history.content[0].code).toMatch(/^NT-\d{4}-\d{5}$/);
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
      billedDays: 1,
      estimatedAmount: 350_000,
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
    const blocked = await failure(discharge(doctor, a.admissionId));
    expect(asRule(blocked, 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    expect(blocked.data.message).toBe('Còn 1 y lệnh chưa thực hiện hoặc hủy (BR-LT-14)');
    await complete(doctor, o.admissionOrderId);
    const res = await discharge(doctor, a.admissionId, { outcome: 'HOME', summary: '  Ổn định, về nhà  ' });
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ status: 'DISCHARGED', dischargeOutcome: 'HOME', dischargeSummary: 'Ổn định, về nhà', unitPrice: 350_000, billedDays: 1, estimatedAmount: 350_000 });
    expect(Number.isInteger(res.data.data.orderLineId)).toBe(true);
    expect(Date.parse(res.data.data.dischargedAt)).not.toBeNaN();
    expect(hasActiveAdmission(4)).toBe(false);
    expect((await list(doctor)).totalElements).toBe(0);
  });

  it('số ngày = ngày xuất − ngày nhập + 1 (ngày VN, gồm hai đầu); giá là giá đã chốt lúc nhập viện', async () => {
    const calls: unknown[][] = [];
    registerVisitOrderSource({
      addServiceLine: (...args) => (calls.push(args), 7000 + calls.length),
    });
    const doctor = await vet();
    const a = await admit(doctor);
    expect((await discharge(doctor, a.admissionId)).status).toBe(200);
    expect(calls[0]).toEqual([2002, 7101, 1, 5, 350_000]);
    // Nhập 3 ngày trước, rồi giá dịch vụ đổi: vẫn 350.000 × 4 (BE: ngày xuất − ngày nhập + 1, không phải tối thiểu 1).
    const b = await admit(doctor);
    patchState((s) => {
      const row = s.admissions.find((x) => x.admissionId === b.admissionId)!;
      row.admittedAt = new Date(Date.now() - 3 * 86_400_000).toISOString();
      row.unitPrice = 400_000;
    });
    expect(data<Admission>(await client.get(`/api/admissions/${b.admissionId}`, doctor))).toMatchObject({ billedDays: 4, estimatedAmount: 1_600_000 });
    const res = await discharge(doctor, b.admissionId);
    expect(res.data.data).toMatchObject({ orderLineId: 7002, billedDays: 4, estimatedAmount: 1_600_000 });
    expect(calls[1]).toEqual([2002, 7101, 4, 5, 400_000]);
  });

  it('thứ tự: sai kiểu 400 → thiếu / dài 400 VALIDATION_FAILED → vai trò 403 → tóm tắt rỗng 400 BR-LT-14 → 404 → chi nhánh 403 → người phụ trách 403 → 409 → còn y lệnh 400 BR-LT-14', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/discharge`;
    expect((await failure(client.post('/api/admissions/abc/discharge', {}, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { outcome: 'DEAD', summary: 'x' }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { outcome: 'HOME', summary: [] }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    // BE đo: VALIDATION_FAILED (thiếu kết cục, thiếu tóm tắt, tóm tắt > 2000) đứng TRƯỚC vai trò.
    expect(await failure(client.post(url, { summary: 'x' }, await nurse()))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('outcome') } });
    expect(await failure(client.post(url, { outcome: 'HOME' }, doctor))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('summary') } });
    expect(await failure(client.post(url, { outcome: 'HOME', summary: 'x'.repeat(2001) }, doctor))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED' } });
    expect((await failure(client.post(url, { outcome: 'HOME', summary: 'x' }, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    // Tóm tắt rỗng: BR-LT-14 đứng ngay sau vai trò, TRƯỚC 404 / chi nhánh / người phụ trách / 409.
    expect(asRule(await failure(client.post('/api/admissions/999/discharge', { outcome: 'HOME', summary: ' ' }, doctor)), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    expect(asRule(await failure(client.post(url, { outcome: 'HOME', summary: '' }, await as('doctor2@store1.vn'))), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
    expect((await failure(client.post('/api/admissions/999/discharge', { outcome: 'HOME', summary: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/discharge', { outcome: 'HOME', summary: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // VET khác (doctor2 không được gán lượt 2002) → 403 phạm vi, đứng trước 409 và trước "còn y lệnh".
    await order(doctor, a.admissionId);
    expect((await failure(client.post(url, { outcome: 'HOME', summary: 'x' }, await as('doctor2@store1.vn')))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: 'x' }, await as('doctor2@store1.vn')))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Ca #1 đã DISCHARGED: tóm tắt rỗng vẫn 400 (đứng trước 409); đủ tóm tắt thì 409.
    expect((await failure(client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: '' }, doctor))).status).toBe(400);
    expect((await failure(client.post('/api/admissions/1/discharge', { outcome: 'HOME', summary: 'Xong' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    // 409 đứng trước "còn y lệnh"; y lệnh chờ chặn xuất viện khi ca còn ADMITTED.
    expect(asRule(await failure(client.post(url, { outcome: 'HOME', summary: 'x' }, doctor)), 'BR-LT-14')).toEqual(BAD_RULE('BR-LT-14'));
  });

  it('kết cục DECEASED xuất viện được ngay, không cần đánh dấu thú mất và không tự đánh dấu (BE đo: BR-LT-14, lễ tân làm sau)', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const done = await discharge(doctor, a.admissionId, { outcome: 'DECEASED', summary: 'Thú mất lúc 10 giờ' });
    expect(done.status).toBe(200);
    expect(done.data.data).toMatchObject({ status: 'DISCHARGED', dischargeOutcome: 'DECEASED' });
    expect(petSummary(4)?.deceasedOn ?? null).toBeNull();
    expect(hasActiveAdmission(4)).toBe(false);
  });
});

// ─── #5, #6 Y lệnh ───

describe('y lệnh nội trú (#5, #6, BR-LT-15)', () => {
  it('ra y lệnh mọi loại: 201, ORDERED, nội dung cắt khoảng trắng, giờ chuẩn hóa ISO; loại không phải thuốc mà gửi dòng đơn thì 400', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const at = '2026-11-07T09:30:00+07:00';
    const res = await client.post(`/api/admissions/${a.admissionId}/orders`, { orderType: 'PROCEDURE', content: '  Truyền dịch  ', scheduledAt: at }, doctor);
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
    // BE đo: loại khác MEDICATION mà có prescriptionItemId → 400 BR-LT-15 (không lặng lẽ bỏ qua).
    const withItem = await failure(client.post(`/api/admissions/${a.admissionId}/orders`, { orderType: 'PROCEDURE', content: 'x', scheduledAt: at, prescriptionItemId: 99 }, doctor));
    expect(asRule(withItem, 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    expect(withItem.data.message).toContain('Chỉ y lệnh thuốc');
  });

  it('y lệnh MEDICATION: phải liên kết dòng đơn thuốc của phòng khám đúng Visit của ca (400 BR-LT-15 nếu thiếu, của lượt khác, hoặc mua ngoài)', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const at = SOON();
    const url = `/api/admissions/${a.admissionId}/orders`;
    expect(asRule(await failure(client.post(url, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: at }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    const seeded = rawState().orders; // dòng đơn thuốc của lượt 61 không thuộc lượt 2002
    expect(seeded.length).toBeGreaterThan(0);
    expect(asRule(await failure(client.post(url, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: at, prescriptionItemId: 2 }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    // Dòng mua ngoài (hết tồn, bác sĩ chọn mua ngoài): BE đo "Dòng đơn thuốc mua ngoài không ra y lệnh được".
    const external = data<{ itemId: number; isExternalPurchase: boolean }>(
      await client.post('/api/visits/2002/prescriptions', { productId: 3, quantity: 999_999, dosageInstructions: 'x', shortageResolution: 'EXTERNAL_PURCHASE' }, doctor),
    );
    expect(external.isExternalPurchase).toBe(true);
    const buyOut = await failure(client.post(url, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: at, prescriptionItemId: external.itemId }, doctor));
    expect(asRule(buyOut, 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    expect(buyOut.data.message).toContain('mua ngoài');
    const item = data<{ itemId: number }>(await client.post('/api/visits/2002/prescriptions', { productId: 3, quantity: 2, dosageInstructions: 'Ngày 2 lần' }, doctor));
    const med = await order(doctor, a.admissionId, { orderType: 'MEDICATION', content: 'Amoxicillin 250mg', scheduledAt: at, prescriptionItemId: item.itemId });
    expect(med).toMatchObject({ orderType: 'MEDICATION', prescriptionItemId: item.itemId });
  });

  it('thứ tự: sai kiểu 400 → thiếu / dài 400 VALIDATION_FAILED (trước vai trò) → VET 403 → 404 → chi nhánh 403 → người phụ trách 403 → 409 → BR-LT-15', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/orders`;
    const body = { orderType: 'FEEDING', content: 'Cho ăn', scheduledAt: SOON() };
    const customer = await as(ROLES.CUSTOMER);
    for (const bad of [{ ...body, orderType: 'BATH' }, { ...body, scheduledAt: 'mai' }, { ...body, scheduledAt: '2026-11-07' }, { ...body, scheduledAt: '2026-11-07T08:00:00' }, { ...body, prescriptionItemId: 'x' }, { ...body, content: [] }]) {
      expect((await failure(client.post(url, bad, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    for (const [patch, field] of [[{ orderType: undefined }, 'orderType'], [{ content: undefined }, 'content'], [{ content: 'x'.repeat(501) }, 'content'], [{ scheduledAt: undefined }, 'scheduledAt'], [{ prescriptionItemId: 0 }, 'prescriptionItemId']] as const) {
      for (const who of [doctor, await desk()]) {
        const res = await failure(client.post(url, { ...body, ...patch }, who));
        expect([res.status, res.data.errorCode, res.data.message.includes(field)]).toEqual([400, 'VALIDATION_FAILED', true]);
      }
    }
    expect((await failure(client.post(url, body, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/admissions/999/orders', body, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/admissions/999/orders', body, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/orders', body, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // VET không phụ trách lượt (doctor2): 403 phạm vi, đứng trước cả 400 BR-LT-15 (nội dung rỗng) lẫn 409.
    const doctor2 = await as('doctor2@store1.vn');
    expect((await failure(client.post(url, body, doctor2))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post(url, { ...body, content: '   ' }, doctor2))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Nội dung toàn khoảng trắng KHÔNG phải VALIDATION_FAILED: BE đo là 400 BR-LT-15, đứng sau 409.
    expect(asRule(await failure(client.post(url, { ...body, content: '   ' }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    expect(asRule(await failure(client.post(url, { ...body, content: '' }, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    // Ca #1 đã DISCHARGED: 409 đứng trước mọi 400 BR-LT-15 (nội dung rỗng, MEDICATION thiếu dòng đơn).
    expect(await failure(client.post('/api/admissions/1/orders', body, doctor))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    expect((await failure(client.post('/api/admissions/1/orders', { ...body, content: ' ' }, doctor))).status).toBe(409);
    expect((await failure(client.post('/api/admissions/1/orders', { ...body, orderType: 'MEDICATION' }, doctor))).status).toBe(409);
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

  it('CARETAKER không thực hiện được y lệnh thuốc: 403 ACCESS_DENIED_SCOPE_MISMATCH (BE đo, không phải ACCESS_DENIED), y lệnh vẫn ORDERED; mọi VET thực hiện được, kể cả không phụ trách lượt', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const item = data<{ itemId: number }>(await client.post('/api/visits/2002/prescriptions', { productId: 3, quantity: 2, dosageInstructions: 'Ngày 2 lần' }, doctor));
    const med = await order(doctor, a.admissionId, { orderType: 'MEDICATION', content: 'Amoxicillin', scheduledAt: SOON(), prescriptionItemId: item.itemId });
    const denied = await failure(client.post(`/api/admission-orders/${med.admissionOrderId}/complete`, { note: 'Đã cho uống' }, await nurse()));
    expect(denied).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
    // Ghi chú rỗng + y lệnh thuốc: vẫn 403 (đứng trước 409 và 400).
    expect((await failure(client.post(`/api/admission-orders/${med.admissionOrderId}/complete`, { note: ' ' }, await nurse()))).status).toBe(403);
    expect(data<AdmissionOrder[]>(await client.get(`/api/admissions/${a.admissionId}/orders`, doctor))[0].status).toBe('ORDERED');
    expect((await complete(await as('doctor2@store1.vn'), med.admissionOrderId, 'VET khác ghi')).status).toBe('DONE');
  });

  it('thứ tự #7: sai kiểu 400 → thiếu / dài 400 VALIDATION_FAILED (trước vai trò) → vai trò → 404 → chi nhánh 403 → CARETAKER với thuốc 403 → 409 → ghi chú rỗng 400 BR-LT-15', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o = await order(doctor, a.admissionId);
    const url = `/api/admission-orders/${o.admissionOrderId}/complete`;
    expect((await failure(client.post('/api/admission-orders/abc/complete', { note: 'x' }, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url, { note: [] }, await desk()))).data.errorCode).toBe('MALFORMED_REQUEST');
    for (const body of [{}, { note: null }, { note: 'x'.repeat(301) }]) {
      for (const who of [doctor, await desk()]) {
        const res = await failure(client.post(url, body, who));
        expect([res.status, res.data.errorCode, res.data.message.includes('note')]).toEqual([400, 'VALIDATION_FAILED', true]);
      }
    }
    expect((await failure(client.post(url, { note: 'x' }, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/admission-orders/999/complete', { note: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admission-orders/999/complete', { note: 'x' }, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    const branch2 = rawState().orders.find((x) => x.admissionId === 2)!.admissionOrderId;
    expect((await failure(client.post(`/api/admission-orders/${branch2}/complete`, { note: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Ghi chú rỗng KHÔNG phải VALIDATION_FAILED: BE đo là 400 BR-LT-15, đứng sau 409.
    for (const body of [{ note: '' }, { note: '   ' }]) {
      expect(asRule(await failure(client.post(url, body, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    }
    // Y lệnh đã DONE (mẫu #1) hoặc đã CANCELLED: 409, kể cả khi ghi chú rỗng.
    expect((await failure(client.post('/api/admission-orders/1/complete', { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/admission-orders/1/complete', { note: ' ' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/admission-orders/3/complete', { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await complete(doctor, o.admissionOrderId);
    expect((await failure(client.post(url, { note: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('thứ tự #8: sai kiểu 400 → thiếu / dài 400 VALIDATION_FAILED (trước vai trò) → VET → 404 → chi nhánh 403 → người phụ trách 403 → 409 → lý do rỗng 400 BR-LT-15', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const o = await order(doctor, a.admissionId);
    const url = `/api/admission-orders/${o.admissionOrderId}/cancel`;
    expect((await failure(client.post(url, { reason: {} }, await nurse()))).data.errorCode).toBe('MALFORMED_REQUEST');
    for (const body of [{}, { reason: null }, { reason: 'x'.repeat(301) }]) {
      for (const who of [doctor, await nurse()]) {
        const res = await failure(client.post(url, body, who));
        expect([res.status, res.data.errorCode, res.data.message.includes('reason')]).toEqual([400, 'VALIDATION_FAILED', true]);
      }
    }
    expect((await failure(client.post(url, { reason: 'x' }, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.post('/api/admission-orders/999/cancel', { reason: 'x' }, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admission-orders/999/cancel', { reason: 'x' }, await nurse()))).data.errorCode).toBe('ACCESS_DENIED');
    const branch2 = rawState().orders.find((x) => x.admissionId === 2)!.admissionOrderId;
    expect((await failure(client.post(`/api/admission-orders/${branch2}/cancel`, { reason: 'x' }, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // VET không phụ trách lượt: 403 phạm vi, đứng trước 409 (y lệnh #1 đã DONE) và trước lý do rỗng.
    const doctor2 = await as('doctor2@store1.vn');
    expect((await failure(client.post(url, { reason: 'x' }, doctor2))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.post('/api/admission-orders/1/cancel', { reason: ' ' }, doctor2))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    for (const body of [{ reason: '' }, { reason: ' ' }]) {
      expect(asRule(await failure(client.post(url, body, doctor)), 'BR-LT-15')).toEqual(BAD_RULE('BR-LT-15'));
    }
    expect((await failure(client.post('/api/admission-orders/1/cancel', { reason: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(client.post('/api/admission-orders/1/cancel', { reason: ' ' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await client.post(url, { reason: 'x' }, doctor);
    expect((await failure(client.post(url, { reason: 'x' }, doctor))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });
});

// ─── #9, #10 Theo dõi ───

const vitals = { temperatureC: 38.6, heartRate: 140, respiratoryRate: 30, weightKg: 18.4, appetite: 'REDUCED', note: ' Thú lờ đờ ', abnormal: false };

describe('chỉ số theo dõi (#9, #10, BR-LT-16)', () => {
  it('VET và CARETAKER ghi: 201, đủ trường, tên người ghi; cân nặng vào hồ sơ cân của thú (BR-KH-04), làm tròn 1 chữ số lẻ (nhiệt độ) và 2 (cân nặng)', async () => {
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
    const rounded = await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, temperatureC: 38.55, weightKg: 5.555 }, doctor);
    expect(rounded.data.data).toMatchObject({ temperatureC: 38.6, weightKg: 5.56 });
  });

  it('abnormal = true: báo VET phụ trách bằng mẫu INPATIENT_OBSERVATION_ABNORMAL_APP (link /admin/inpatient) khi người ghi là CARETAKER; VET tự ghi thì không tự báo mình', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const unread = async () =>
      data<PageResponse<{ type: string; title: string; body: string; linkUrl: string | null }>>(await client.get('/api/me/notifications', { params: { unreadOnly: true }, ...doctor })).content.filter(
        (n) => n.type === 'INPATIENT_OBSERVATION_ABNORMAL_APP',
      );
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: true, note: 'Tự ghi' }, doctor);
    expect(await unread()).toHaveLength(0);
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: true, temperatureC: 40.1 }, await nurse());
    const [notice] = await unread();
    expect(notice).toMatchObject({ title: 'Lu có dấu hiệu bất thường khi nội trú', linkUrl: '/admin/inpatient' });
    expect(notice.body).toContain(`ca nội trú ${a.code}`);
    expect(notice.body).toMatch(/^Theo dõi ngày \d{2}\/\d{2}\/\d{4} của Lu /);
    await client.post(`/api/admissions/${a.admissionId}/observations`, { abnormal: false, temperatureC: 38.5 }, await nurse());
    expect(await unread()).toHaveLength(1);
  });

  it('thứ tự: sai kiểu 400 → thiếu abnormal / ghi chú > 500 400 (trước vai trò) → vai trò → 404 → chi nhánh 403 → 409 → BR-LT-16', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/observations`;
    const customer = await as(ROLES.CUSTOMER);
    for (const bad of [{ ...vitals, temperatureC: 'nóng' }, { ...vitals, heartRate: 80.5 }, { ...vitals, appetite: 'MAYBE' }, { ...vitals, note: [] }, { ...vitals, abnormal: 'có' }]) {
      expect((await failure(client.post(url, bad, customer))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    // BE (Jackson) nhận số dạng chuỗi cho trường số: heartRate '30' vẫn 201.
    expect((await client.post(url, { ...vitals, respiratoryRate: '30' }, doctor)).status).toBe(201);
    const noFlag = { ...vitals, abnormal: undefined };
    expect(await failure(client.post(url, noFlag, await desk()))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('abnormal') } });
    expect((await failure(client.post(url, vitals, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect(await failure(client.post(url, noFlag, doctor))).toMatchObject({ status: 400, data: { errorCode: 'VALIDATION_FAILED', message: expect.stringContaining('abnormal') } });
    expect((await failure(client.post(url, { ...vitals, note: 'x'.repeat(501) }, doctor))).data.message).toContain('note');
    expect((await failure(client.post('/api/admissions/999/observations', noFlag, doctor))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/admissions/999/observations', vitals, doctor))).status).toBe(404);
    expect((await failure(client.post('/api/admissions/2/observations', vitals, doctor))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Ca #1 đã DISCHARGED: 409 đứng TRƯỚC BR-LT-16 (BE đo: ghi rỗng trên ca đã xuất viện vẫn 409).
    expect(await failure(client.post('/api/admissions/1/observations', vitals, doctor))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    expect((await failure(client.post('/api/admissions/1/observations', { abnormal: true }, doctor))).status).toBe(409);
  });

  it('BR-LT-16: không có chỉ số hay ghi chú nào (abnormal một mình không đủ), giá trị ngoài giới hạn lưu trữ: 400 đúng câu của BE và không ghi gì; biên hợp lệ ghi được', async () => {
    const doctor = await vet();
    const a = await admit(doctor);
    const url = `/api/admissions/${a.admissionId}/observations`;
    for (const body of [{ abnormal: false }, { abnormal: true }, { abnormal: false, note: '   ' }, { abnormal: false, temperatureC: null, note: null }]) {
      const res = await failure(client.post(url, body, doctor));
      expect(asRule(res, 'BR-LT-16')).toEqual(BAD_RULE('BR-LT-16'));
      expect(res.data.message).toBe('Vui lòng nhập ít nhất một chỉ số hoặc ghi chú theo dõi (BR-LT-16)');
    }
    const cases: [object, string][] = [
      [{ temperatureC: 1000 }, 'Nhiệt độ vượt giới hạn lưu trữ'],
      [{ temperatureC: 999.96 }, 'Nhiệt độ vượt giới hạn lưu trữ'],
      [{ heartRate: 0 }, 'Nhịp tim phải từ 1 đến 32767'],
      [{ heartRate: 40000 }, 'Nhịp tim phải từ 1 đến 32767'],
      [{ respiratoryRate: -3 }, 'Nhịp thở phải từ 1 đến 32767'],
      [{ weightKg: 0 }, 'Cân nặng phải lớn hơn 0'],
      [{ weightKg: -1 }, 'Cân nặng phải lớn hơn 0'],
      [{ weightKg: 0.004 }, 'Cân nặng phải lớn hơn 0'],
      [{ weightKg: 10000 }, 'Cân nặng vượt giới hạn lưu trữ'],
    ];
    for (const [patch, text] of cases) {
      const res = await failure(client.post(url, { abnormal: false, ...patch }, doctor));
      expect([JSON.stringify(patch), ...asRule(res, 'BR-LT-16'), res.data.message]).toEqual([JSON.stringify(patch), ...BAD_RULE('BR-LT-16'), `${text} (BR-LT-16)`]);
    }
    expect(data<PageResponse<Observation>>(await client.get(url, doctor)).totalElements).toBe(0);
    // Biên hợp lệ theo BE: nhiệt độ 0 và âm không bị chặn (khoảng lâm sàng chưa chốt, A4), cân từ 0.005.
    expect((await client.post(url, { abnormal: false, temperatureC: 999.9, heartRate: 32767, respiratoryRate: 1, weightKg: 0.005 }, doctor)).data.data).toMatchObject({ weightKg: 0.01 });
    expect((await client.post(url, { abnormal: false, temperatureC: 0 }, doctor)).data.data.temperatureC).toBe(0);
    expect((await client.post(url, { abnormal: false, temperatureC: -5.55 }, doctor)).data.data.temperatureC).toBe(-5.6);
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
  it('còn ADMITTED: 409 INVALID_STATE_TRANSITION (BE đo, không phải 400); xuất viện xong thì hoàn tất được', async () => {
    const doctor = await vet();
    await client.put('/api/visits/2002/medical-record', { diagnosis: 'Khỏe' }, doctor);
    const a = await admit(doctor);
    const blocked = await failure(client.post('/api/visits/2002/complete', undefined, doctor));
    expect(blocked).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
    expect(blocked.data.message).toContain('còn ca nội trú đang điều trị');
    await discharge(doctor, a.admissionId);
    expect(data<Visit>(await client.post('/api/visits/2002/complete', undefined, doctor))).toMatchObject({ visitId: 2002, status: 'COMPLETED' });
  });

  it('guard đứng TRƯỚC kiểm chẩn đoán (BR-KB-02): lượt chưa có chẩn đoán mà còn ca ADMITTED vẫn 409 (BE đo: visit 10 chưa chẩn đoán → 409)', async () => {
    const doctor = await vet();
    await admit(doctor);
    const blocked = await failure(client.post('/api/visits/2002/complete', undefined, doctor));
    expect(blocked).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
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
      JSON.stringify({ admissions: [{ admissionId: 10, code: 'NT-2610-00001', visitId: 61, petId: 1, customerId: 1, branchId: 1, serviceId: 7101, unitPrice: 350000, status: 'ADMITTED', admittedBy: 5, admittedAt: '2026-10-01T03:00:00.000Z' }] }),
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
