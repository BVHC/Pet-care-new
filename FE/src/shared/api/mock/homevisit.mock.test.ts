import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PageResponse } from '../../types';
import type { Appointment, RestrictionStatus } from '../../types/appointment';
import type { HomeVisitAppointment, HomeVisitAppointmentSummary, HomeVisitStarted } from '../../types/homevisit';
import type { Visit } from '../../types/visit';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { defaultHomeVisitFee, registerHomeVisitFeeSource } from './homevisit.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (appointment.mock.ts `seedHomeVisits`, nạp lười ở lần đầu một route tại nhà chạy; GĐ1 chiếm mã 1–11):
//   12 Tôm (khach@petcare.vn) BOOKED hôm nay 16:30 · 13 Bim BOOKED sau 2 ngày · 14 Mochi COMPLETED (lượt 61) · 15 Bim CANCELLED · 16 Miu (hồ sơ quầy #4) NO_SHOW · 17 Miu BOOKED ở chi nhánh 2.
// Mọi thú mẫu còn lượt mở của visit.mock: Tôm có lượt 2102 (WAITING) nên "Bắt đầu" lần đầu ra 400 BR-TN-01 cho tới khi lễ tân hủy lượt đó.

const TODAY_ID = 12;
const SOON_ID = 13;
const DONE_ID = 14;
const CANCELLED_ID = 15;
const NO_SHOW_ID = 16;
const BRANCH2_ID = 17;

let client: AxiosInstance;

beforeEach(() => {
  registerHomeVisitFeeSource(defaultHomeVisitFee);
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
const ruleOf = (res: AxiosResponse) => [res.status, res.data.errorCode, /\((BR-[A-Z]+-\d+)\)$/.exec(res.data.message)?.[1]];

const customer = () => as(ROLES.CUSTOMER);
const desk = () => as(ROLES.RECEPTIONIST);
const vet = () => as(ROLES.VET);
const today = () => vnDate();

/** Khung AVAILABLE thứ `index` của `serviceId` 1 ở chi nhánh 1, từ `offset` ngày tới (bỏ qua ngày nghỉ). */
async function freeSlot(auth: Auth, offset = 5, index = 0) {
  for (let d = offset; d < offset + 14; d += 1) {
    const slotDate = addDays(today(), d);
    const slots = data<{ slotStart: string; status: string }[]>(await client.get('/api/branches/1/available-slots', { params: { serviceId: 1, date: slotDate }, ...auth }));
    const free = slots.filter((s) => s.status === 'AVAILABLE');
    if (free.length > index) return { slotDate, slotStart: free[index].slotStart };
  }
  throw new Error('không có khung trống');
}
const bodyOf = async (auth: Auth, over: object = {}) => ({ petId: 3, serviceId: 1, branchId: 1, ...(await freeSlot(auth)), addressId: 1, contactPhone: '0912345678', ...over });
const create = async (auth: Auth, over: object = {}) => data<HomeVisitAppointment>(await client.post('/api/home-visit-appointments', await bodyOf(auth, over), auth));
const post = (auth: Auth, path: string, body: object = {}) => client.post(path, body, auth);
const listOf = async (auth: Auth, path: string, params: object = {}) => data<PageResponse<HomeVisitAppointmentSummary>>(await client.get(path, { params, ...auth }));

/** Sửa state lịch hẹn thô (nạp mẫu trước bằng một lần gọi route tại nhà). */
interface RawRow { appointmentId: number; slotDate: string; slotStart: string; status: string; petId: number; [k: string]: unknown }
const patchAppointments = (fn: (rows: RawRow[]) => void) => {
  const s = JSON.parse(localStorage.getItem('petcare-mock-appointments')!) as { appointments: RawRow[] };
  fn(s.appointments);
  localStorage.setItem('petcare-mock-appointments', JSON.stringify(s));
};
const hhmm = () => new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(Date.now());

// ─── Quyền (đúng dòng "Quyền:" của yaml) và 401 ───

interface Endpoint {
  name: string;
  call: (auth: Auth) => Promise<unknown>;
  allowed: RoleName[];
}
const ENDPOINTS: Endpoint[] = [
  {
    name: '#1 đặt lịch',
    call: (a) => client.post('/api/home-visit-appointments', { petId: 3, serviceId: 1, branchId: 1, slotDate: addDays(today(), 6), slotStart: '09:00', addressId: 1, contactPhone: '0912345678' }, a),
    allowed: ['CUSTOMER', 'RECEPTIONIST'],
  },
  { name: '#2 của tôi', call: (a) => client.get('/api/me/home-visit-appointments', a), allowed: ['CUSTOMER'] },
  { name: '#3 của chi nhánh', call: (a) => client.get('/api/home-visit-appointments', a), allowed: ['RECEPTIONIST', 'VET'] },
  { name: '#4 chi tiết', call: (a) => client.get(`/api/home-visit-appointments/${TODAY_ID}`, a), allowed: ['CUSTOMER', 'RECEPTIONIST', 'VET'] },
  { name: '#5 bắt đầu', call: (a) => client.post(`/api/home-visit-appointments/${TODAY_ID}/start`, {}, a), allowed: ['VET'] },
  { name: '#6 không gặp được', call: (a) => client.post(`/api/home-visit-appointments/${TODAY_ID}/mark-unreachable`, { note: 'Không ai ra mở cửa' }, a), allowed: ['VET'] },
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

// ─── #1 Đặt lịch ───

describe('đặt lịch khám tại nhà (#1, UC43, BR-LH-13)', () => {
  it('khách đặt cho thú của mình với địa chỉ trong sổ: 201, BOOKED, chốt phí và địa chỉ, đủ trường', async () => {
    const auth = await customer();
    const slot = await freeSlot(auth);
    const res = await client.post('/api/home-visit-appointments', { petId: 3, serviceId: 1, branchId: 1, ...slot, addressId: 2, contactPhone: '0987000111', note: '  Bé hay trốn  ' }, auth);
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã đặt lịch khám tại nhà', code: 201 });
    expect(res.data.data).toEqual({
      appointmentId: expect.any(Number),
      code: expect.stringMatching(/^LH-\d{6}-\d{4}$/),
      petId: 3,
      petName: 'Tôm',
      customerId: 1,
      serviceId: 1,
      serviceName: 'Khám tổng quát',
      branchId: 1,
      ...slot,
      status: 'BOOKED',
      homeAddress: '45 Láng Hạ, Láng Hạ, Hà Nội',
      homeContactPhone: '0987000111',
      homeFee: 100_000,
      note: 'Bé hay trốn',
      visitId: null,
      createdAt: expect.any(String),
    });
    expect(res.data.data.appointmentId).toBeGreaterThan(BRANCH2_ID);
  });

  it('phí đã chốt lúc đặt: đổi nguồn phí sau đó không đổi lịch cũ; nguồn trả null (chi nhánh chưa bật) → 400 BR-LH-13', async () => {
    const auth = await customer();
    registerHomeVisitFeeSource(() => 150_000);
    const first = await create(auth);
    registerHomeVisitFeeSource(() => 90_000);
    expect(first.homeFee).toBe(150_000);
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${first.appointmentId}`, auth)).homeFee).toBe(150_000);
    registerHomeVisitFeeSource(() => null);
    const off = await failure(client.post('/api/home-visit-appointments', await bodyOf(auth), auth));
    expect(ruleOf(off)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-13']);
  });

  it('lễ tân đặt hộ ở chi nhánh mình với địa chỉ nhập tay hoặc địa chỉ trong sổ của khách; khách thấy ở danh sách của mình', async () => {
    const reception = await desk();
    const typed = await create(reception, { petId: 5, address: '  Số 9 ngõ 105 Xuân Thủy, Cầu Giấy  ', addressId: undefined, customerId: 4 });
    expect(typed).toMatchObject({ customerId: 4, petName: 'Miu', homeAddress: 'Số 9 ngõ 105 Xuân Thủy, Cầu Giấy' });
    const fromBook = await create(reception, { petId: 3, addressId: 2, customerId: 1 });
    expect(fromBook.homeAddress).toBe('45 Láng Hạ, Láng Hạ, Hà Nội');
    expect(listedFor(await listOf(await customer(), '/api/me/home-visit-appointments')).includes(fromBook.appointmentId)).toBe(true);
    expect(listedFor(await listOf(await customer(), '/api/me/home-visit-appointments')).includes(typed.appointmentId)).toBe(false);
  });

  it('thứ tự: body sai kiểu 400 (trước vai trò) → vai trò 403 → thiếu / sai định dạng 400 VALIDATION_FAILED → lễ tân chi nhánh khác 403 → 404', async () => {
    const auth = await customer();
    const ok = await bodyOf(auth);
    for (const patch of [{ petId: 'abc' }, { petId: 1.5 }, { slotDate: '2026-02-30' }, { slotDate: 'mai' }, { slotStart: '9h' }, { slotStart: '25:00' }, { note: [] }, { addressId: {} }]) {
      expect((await failure(client.post('/api/home-visit-appointments', { ...ok, ...patch }, await as(ROLES.VET)))).data.errorCode, JSON.stringify(patch)).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(client.post('/api/home-visit-appointments', undefined, await as(ROLES.VET)))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/home-visit-appointments', ok, await as(ROLES.VET)))).data.errorCode).toBe('ACCESS_DENIED');

    for (const [patch, field] of [
      [{ petId: undefined }, 'petId'],
      [{ serviceId: 0 }, 'serviceId'],
      [{ branchId: -1 }, 'branchId'],
      [{ slotDate: undefined }, 'slotDate'],
      [{ slotStart: undefined }, 'slotStart'],
      [{ contactPhone: undefined }, 'contactPhone'],
      [{ contactPhone: '12345' }, 'contactPhone'],
      [{ contactPhone: '091234567890123' }, 'contactPhone'],
      [{ address: 'x'.repeat(301) }, 'address'],
      [{ note: 'x'.repeat(301) }, 'note'],
      [{ customerId: 0 }, 'customerId'],
    ] as const) {
      const res = await failure(client.post('/api/home-visit-appointments', { ...ok, ...patch }, auth));
      expect([JSON.stringify(patch), res.status, res.data.errorCode, res.data.message.includes(field)]).toEqual([JSON.stringify(patch), 400, 'VALIDATION_FAILED', true]);
    }
    // Lễ tân đặt cho chi nhánh khác: 403 phạm vi (message chung), đứng trước 404 của thú.
    const other = await failure(client.post('/api/home-visit-appointments', { ...ok, branchId: 2, petId: 9999 }, await desk()));
    expect(other).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
    expect(other.data.message).not.toMatch(/\(BR-/);
    // 404: thú của khách khác (khách), thú không có, khách không có, thú lệch khách, chi nhánh (khách: cả chi nhánh chưa ACTIVE), dịch vụ, địa chỉ.
    for (const [patch, what] of [
      [{ petId: 5 }, 'thú của khách khác'],
      [{ petId: 9999 }, 'thú không có'],
      [{ branchId: 2 }, 'chi nhánh chưa hoạt động'],
      [{ branchId: 9999 }, 'chi nhánh không có'],
      [{ serviceId: 9999 }, 'dịch vụ không có'],
      [{ addressId: 9999 }, 'địa chỉ không có'],
    ] as const) {
      expect([what, (await failure(client.post('/api/home-visit-appointments', { ...ok, ...patch }, auth))).status]).toEqual([what, 404]);
    }
    const reception = await desk();
    expect((await failure(client.post('/api/home-visit-appointments', { ...ok, customerId: 9999 }, reception))).status).toBe(404);
    expect((await failure(client.post('/api/home-visit-appointments', { ...ok, customerId: 4 }, reception))).status).toBe(404);
  });

  it('BR-LH-13: dịch vụ không phải khám, địa chỉ sai (cả hai / không cái nào / nhập tay của khách / sổ của người khác), chi nhánh chưa bật; thú mất', async () => {
    const auth = await customer();
    const reception = await desk();
    const expectRule = async (a: Auth, over: object, ruleId: string, message?: RegExp) => {
      const res = await failure(client.post('/api/home-visit-appointments', await bodyOf(a, over), a));
      expect([JSON.stringify(over), ...ruleOf(res)]).toEqual([JSON.stringify(over), 400, 'BUSINESS_RULE_VIOLATION', ruleId]);
      if (message) expect(res.data.message).toMatch(message);
    };
    await expectRule(auth, { serviceId: 2 }, 'BR-LH-13', /phải là dịch vụ khám/);
    await expectRule(auth, { serviceId: 7001 }, 'BR-LH-13');
    await expectRule(auth, { addressId: undefined }, 'BR-LH-13', /sổ địa chỉ/);
    await expectRule(auth, { addressId: undefined, address: 'Số 1 Đại Cồ Việt' }, 'BR-LH-13', /Khách chỉ chọn/);
    await expectRule(auth, { addressId: 3 }, 'BR-LH-13', /không thuộc sổ/);
    await expectRule(reception, { addressId: 1, address: 'Số 1 Đại Cồ Việt' }, 'BR-LH-13', /một trong/);
    await expectRule(reception, { addressId: undefined, address: '   ' }, 'BR-LH-13');
    await expectRule(reception, { addressId: 3 }, 'BR-LH-13', /không thuộc sổ/);
    registerHomeVisitFeeSource((branchId) => (branchId === 1 ? null : 100_000));
    await expectRule(auth, {}, 'BR-LH-13', /chưa bật/);
    registerHomeVisitFeeSource(defaultHomeVisitFee);
    // Thú đã mất: BR-LH-13 theo chữ yaml (mock thú đánh dấu thẳng vào state vì thú Tôm còn lượt mở).
    await create(reception, { petId: 5, address: 'Số 9 Xuân Thủy', addressId: undefined, customerId: 4 }); // nạp state thú
    const pets = JSON.parse(localStorage.getItem('petcare-mock-pets')!) as { pets: { petId: number; deceasedOn: string | null }[] };
    pets.pets.find((p) => p.petId === 3)!.deceasedOn = '2026-11-01';
    localStorage.setItem('petcare-mock-pets', JSON.stringify(pets));
    await expectRule(auth, {}, 'BR-LH-13', /đã mất/);
  });

  it('BR-LH-04 (khách: sau ≥ 24 giờ; lễ tân: khung chưa bắt đầu), BR-LH-02 (khung không hợp lệ), BR-LH-05 (giới hạn lịch của thú), BR-LH-09 (khách bị hạn chế, lễ tân thì không)', async () => {
    const auth = await customer();
    const reception = await desk();
    const book = (a: Auth, over: object) => failure(client.post('/api/home-visit-appointments', over, a));
    const base = await bodyOf(auth);
    expect(ruleOf(await book(auth, { ...base, slotDate: today(), slotStart: '23:30' }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-04']);
    expect(ruleOf(await book(auth, { ...base, slotDate: addDays(today(), 40) }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-04']);
    expect(ruleOf(await book(reception, { ...base, slotDate: addDays(today(), -1) }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-04']);
    expect(ruleOf(await book(auth, { ...base, slotStart: '03:00' }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-02']);
    // Bim đã có 2 lịch BOOKED (1 thường + lịch tại nhà #13): lịch thứ ba bị chặn.
    expect(ruleOf(await book(auth, { ...base, petId: 2 }))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-05']);
    // Một thú không có hai lịch Khám/Tiêm cùng ngày (lịch tại nhà cũng tính): Lu đã có lịch Tái khám 10:00 ngày mai (lịch thường #11), đặt thêm khung khác cùng ngày bị chặn, không phải lỗi trùng khung.
    const recheck = data<Appointment>(await client.get('/api/appointments/11', reception));
    const slots = data<{ slotStart: string; status: string }[]>(await client.get('/api/branches/1/available-slots', { params: { serviceId: 1, date: recheck.slotDate }, ...reception }));
    const other = slots.find((x) => x.status === 'AVAILABLE' && x.slotStart !== recheck.slotStart)!;
    const sameDay = await book(reception, { ...base, petId: 4, address: 'Số 8 Nguyễn Chí Thanh', addressId: undefined, slotDate: recheck.slotDate, slotStart: other.slotStart });
    expect(ruleOf(sameDay)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-05']);
    expect(sameDay.data.message).toMatch(/trong ngày/);
    const sameSlot = await book(reception, { ...base, petId: 4, address: 'Số 8 Nguyễn Chí Thanh', addressId: undefined, slotDate: recheck.slotDate, slotStart: recheck.slotStart });
    expect(sameSlot.data.message).toMatch(/trùng khung/);
    // Khách #3 (Lu, hồ sơ quầy) đang bị hạn chế đặt online: lễ tân đặt hộ vẫn được (BR-LH-09 chỉ chặn đặt online).
    const lu = await create(reception, { petId: 4, address: 'Số 8 Nguyễn Chí Thanh', addressId: undefined, ...(await freeSlot(reception, 12)) });
    expect(lu).toMatchObject({ customerId: 3, petName: 'Lu' });
  });

  it('quota khung giờ dùng chung với lịch thường (A1): khung vừa hết chỗ → 409 CONCURRENCY_CONFLICT, không phải 400', async () => {
    const auth = await customer();
    const slot = await freeSlot(auth, 7);
    await client.put('/api/branches/1/slot-quotas', { serviceGroup: 'MEDICAL', ...slot, quota: 1 }, await as(ROLES.BRANCH_MANAGER));
    const first = await create(auth, { ...slot });
    expect(first).toMatchObject({ ...slot, status: 'BOOKED' });
    const full = await failure(client.post('/api/home-visit-appointments', await bodyOf(await desk(), { ...slot, petId: 5, address: 'Số 9 Xuân Thủy', addressId: undefined, customerId: 4 }), await desk()));
    expect(full).toMatchObject({ status: 409, data: { errorCode: 'CONCURRENCY_CONFLICT' } });
    expect(full.data.message).toMatch(/\(BR-LH-03\)$/);
    // Lịch thường cũng thấy khung đó đã đầy.
    const slots = data<{ slotStart: string; status: string; booked: number }[]>(await client.get('/api/branches/1/available-slots', { params: { serviceId: 1, date: slot.slotDate }, ...auth }));
    expect(slots.find((s) => s.slotStart === slot.slotStart)).toMatchObject({ status: 'FULL', booked: 1 });
    // Hủy lịch tại nhà bằng endpoint của appointment-v1 (BR-LH-14) trả lại quota.
    expect((await post(auth, `/api/appointments/${first.appointmentId}/cancel`, { reason: 'Đổi ý' })).data.data.status).toBe('CANCELLED');
    expect((await create(await desk(), { ...slot, petId: 5, address: 'Số 9 Xuân Thủy', addressId: undefined, customerId: 4 })).slotStart).toBe(slot.slotStart);
  });

  it('khách còn chờ quyết định liên kết hồ sơ (khachmoi@petcare.vn): không có thú nào để đặt (thêm thú bị chặn BR-TK-19 từ customer-v1, thú của người khác là 404)', async () => {
    const pending = await as('khachmoi@petcare.vn');
    const add = await failure(client.post('/api/me/pets', { name: 'Kem', species: 'DOG', sex: 'MALE' }, pending));
    expect(ruleOf(add)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-TK-19']);
    const res = await failure(client.post('/api/home-visit-appointments', await bodyOf(pending), pending));
    expect(res.status).toBe(404);
    expect(listedFor(await listOf(pending, '/api/me/home-visit-appointments'))).toEqual([]);
  });
});

// ─── #2, #3, #4 Xem ───

/** Mã của các dòng trong danh sách. */
const listedFor = (page: PageResponse<HomeVisitAppointmentSummary>) => page.content.map((a) => a.appointmentId);

describe('xem lịch khám tại nhà (#2, #3, #4)', () => {
  it('#2: lịch của khách, ngày khám giảm dần (trùng thì mã giảm dần), chỉ thú của mình; lọc status; phân trang', async () => {
    const auth = await customer();
    const page = await listOf(auth, '/api/me/home-visit-appointments');
    expect(listedFor(page)).toEqual([SOON_ID, TODAY_ID, CANCELLED_ID, DONE_ID]);
    expect(page.content[1]).toEqual({
      appointmentId: TODAY_ID,
      code: expect.stringMatching(/^LH-/),
      petName: 'Tôm',
      serviceName: 'Khám tổng quát',
      slotDate: today(),
      slotStart: '16:30',
      status: 'BOOKED',
      homeAddress: '12 Trần Thái Tông, Dịch Vọng Hậu, Hà Nội',
      homeFee: 100_000,
    });
    expect(listedFor(await listOf(auth, '/api/me/home-visit-appointments', { status: 'BOOKED' }))).toEqual([SOON_ID, TODAY_ID]);
    expect(await listOf(auth, '/api/me/home-visit-appointments', { size: 1, page: 2 })).toMatchObject({ page: 2, size: 1, totalElements: 4, totalPages: 4 });
    expect((await listOf(auth, '/api/me/home-visit-appointments', { page: -1, size: 0 })).size).toBe(1);
    expect((await failure(client.get('/api/me/home-visit-appointments', { params: { status: 'GONE' }, ...auth }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/me/home-visit-appointments', { params: { page: 'x' }, ...(await vet()) }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('#3: chi nhánh của người gọi; vắng date = hôm nay; lọc date và status; sắp giờ tăng dần; không lộ chi nhánh 2', async () => {
    const reception = await desk();
    expect(listedFor(await listOf(reception, '/api/home-visit-appointments'))).toEqual([TODAY_ID]);
    expect(listedFor(await listOf(await vet(), '/api/home-visit-appointments', { date: addDays(today(), 2) }))).toEqual([SOON_ID]);
    expect(listedFor(await listOf(reception, '/api/home-visit-appointments', { date: addDays(today(), -4), status: 'NO_SHOW' }))).toEqual([NO_SHOW_ID]);
    expect((await listOf(reception, '/api/home-visit-appointments', { date: addDays(today(), 4) })).totalElements).toBe(0);
    expect((await listOf(reception, '/api/home-visit-appointments', { status: 'COMPLETED' })).totalElements).toBe(0);
    // Hai lịch cùng ngày: giờ sớm trước, trùng giờ thì mã nhỏ trước.
    patchAppointments((rows) => {
      rows.find((r) => r.appointmentId === SOON_ID)!.slotDate = today();
      rows.find((r) => r.appointmentId === SOON_ID)!.slotStart = '08:00';
    });
    expect(listedFor(await listOf(reception, '/api/home-visit-appointments'))).toEqual([SOON_ID, TODAY_ID]);
    expect((await failure(client.get('/api/home-visit-appointments', { params: { date: '2026-13-01' }, ...reception }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/home-visit-appointments', { params: { status: 'OPEN' }, ...reception }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('#4: khách xem lịch của mình; lịch của người khác và lịch thường (không phải lịch tại nhà) → 404; nhân viên chi nhánh khác 403 (không mã rule)', async () => {
    const auth = await customer();
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${DONE_ID}`, auth))).toMatchObject({
      appointmentId: DONE_ID,
      status: 'COMPLETED',
      visitId: 61,
      petName: 'Mochi',
      homeAddress: '12 Trần Thái Tông, Dịch Vọng Hậu, Hà Nội',
    });
    expect((await failure(client.get(`/api/home-visit-appointments/${NO_SHOW_ID}`, auth))).status).toBe(404);
    expect((await failure(client.get('/api/home-visit-appointments/1', auth))).status).toBe(404);
    expect((await failure(client.get('/api/home-visit-appointments/1', await desk()))).status).toBe(404);
    expect((await failure(client.get('/api/home-visit-appointments/9999', await vet()))).status).toBe(404);
    const other = await failure(client.get(`/api/home-visit-appointments/${BRANCH2_ID}`, await vet()));
    expect(other).toMatchObject({ status: 403, data: { errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH' } });
    expect(other.data.message).not.toMatch(/\(BR-/);
    expect((await failure(client.get('/api/home-visit-appointments/abc', auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${NO_SHOW_ID}`, await vet())).status).toBe('NO_SHOW');
  });
});

// ─── #5 Bắt đầu ───

/** Lễ tân hủy lượt đang chờ của Tôm (2102) để Tôm hết lượt mở (BR-TN-01). */
const freePet = async () => post(await desk(), '/api/visits/2102/cancel', { reason: 'Khách chuyển sang khám tại nhà' });

describe('bắt đầu khám tại nhà (#5, UC51, BR-KB-07, BR-BH-16)', () => {
  it('thú còn lượt mở khác → 400 BR-TN-01 và không ghi gì; hủy lượt đó rồi bắt đầu được', async () => {
    const doctor = await vet();
    const busy = await failure(post(doctor, `/api/home-visit-appointments/${TODAY_ID}/start`));
    expect(ruleOf(busy)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-TN-01']);
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${TODAY_ID}`, doctor))).toMatchObject({ status: 'BOOKED', visitId: null });
    await freePet();
    const res = await post(doctor, `/api/home-visit-appointments/${TODAY_ID}/start`);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã bắt đầu khám tại nhà');
    const started = data<HomeVisitStarted>(res);
    expect(started).toEqual({ appointmentId: TODAY_ID, visitId: expect.any(Number), orderId: expect.any(Number) });

    // Lượt tạo ra: IN_PROGRESS gán đúng VET, gắn lịch hẹn, có Order; lịch → CHECKED_IN kèm visitId (BR-KB-07).
    const visit = data<Visit>(await client.get(`/api/visits/${started.visitId}`, doctor));
    expect(visit).toMatchObject({ status: 'IN_PROGRESS', assigneeId: 5, appointmentId: TODAY_ID, petId: 3, customerId: 1, serviceGroup: 'MEDICAL', orderId: started.orderId });
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${TODAY_ID}`, doctor))).toMatchObject({ status: 'CHECKED_IN', visitId: started.visitId });
    // Không vào hàng đợi chi nhánh, nhưng nằm trong "lượt của tôi" của VET.
    expect(data<Visit[]>(await client.get('/api/visits', await desk())).some((v) => v.visitId === started.visitId)).toBe(false);
    expect(data<Visit[]>(await client.get('/api/me/queue', doctor)).some((v) => v.visitId === started.visitId)).toBe(true);
    // Order VISIT mở với dòng dịch vụ khám của lịch.
    const order = data<{ source: string; status: string; lines: { lineType: string; serviceId: number }[] }>(await client.get(`/api/orders/${started.orderId}`, await desk()));
    expect(order).toMatchObject({ source: 'VISIT', status: 'OPEN' });
    expect(order.lines[0]).toMatchObject({ lineType: 'SERVICE', serviceId: 1 });
    // Bắt đầu lần hai: 409 (đứng trước BR-TN-01 dù thú đang có chính lượt vừa tạo).
    expect(ruleOf(await failure(post(doctor, `/api/home-visit-appointments/${TODAY_ID}/start`)))).toEqual([409, 'INVALID_STATE_TRANSITION', undefined]);
  });

  it('hết vòng đời (03 #22, BR-LH-11, BR-BH-16): hoàn tất lượt bằng visit-v1 #10 thì lịch → COMPLETED, Order → PENDING chờ thu tại chi nhánh, thú hết lượt mở', async () => {
    const doctor = await vet();
    await freePet();
    const started = data<HomeVisitStarted>(await post(doctor, `/api/home-visit-appointments/${TODAY_ID}/start`));
    // Lượt tại nhà là lượt Khám: phải có chẩn đoán trước khi hoàn tất (BR-KB-02).
    expect(ruleOf(await failure(post(doctor, `/api/visits/${started.visitId}/complete`)))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-KB-02']);
    await client.put(`/api/visits/${started.visitId}/medical-record`, { diagnosis: 'Hamster viêm da nhẹ' }, doctor);
    expect(data<Visit>(await post(doctor, `/api/visits/${started.visitId}/complete`)).status).toBe('COMPLETED');

    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${TODAY_ID}`, doctor))).toMatchObject({ status: 'COMPLETED', visitId: started.visitId });
    const reception = await desk();
    expect(data<{ status: string }>(await client.get(`/api/orders/${started.orderId}`, reception)).status).toBe('PENDING');
    // Thú hết lượt mở: lễ tân tiếp nhận được Tôm như khách vãng lai. Lịch COMPLETED chuyển giao diện nhưng vẫn chiếm quota khung (BR-LH-03).
    expect((await post(reception, '/api/visits', { petId: 3, serviceId: 1 })).status).toBe(201);
    expect((await failure(post(doctor, `/api/home-visit-appointments/${TODAY_ID}/mark-unreachable`, { note: 'x' }))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('thứ tự: path 400 → VET 403 → 404 → chi nhánh 403 → không phải lịch tại nhà / ngoài ngày / thú mất 400 BR-KB-07 → 409', async () => {
    const doctor = await vet();
    expect((await failure(post(doctor, '/api/home-visit-appointments/abc/start'))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(post(await desk(), `/api/home-visit-appointments/${TODAY_ID}/start`))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(post(doctor, '/api/home-visit-appointments/9999/start'))).status).toBe(404);
    expect((await failure(post(doctor, `/api/home-visit-appointments/${BRANCH2_ID}/start`))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Lịch hẹn thường (mã 1): 400 BR-KB-07, không phải 404.
    expect(ruleOf(await failure(post(doctor, '/api/home-visit-appointments/1/start')))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-KB-07']);
    for (const id of [SOON_ID, DONE_ID, NO_SHOW_ID]) {
      const res = await failure(post(doctor, `/api/home-visit-appointments/${id}/start`));
      expect([id, ...ruleOf(res)]).toEqual([id, 400, 'BUSINESS_RULE_VIOLATION', 'BR-KB-07']);
      expect(res.data.message).toMatch(/trong ngày hẹn/);
    }
    // Thú đã mất: lịch BOOKED bị phòng khám hủy (sweep) nhưng guard 400 vẫn đứng trước 409.
    patchAppointments(() => undefined); // đảm bảo state đã nạp
    const pets = JSON.parse(localStorage.getItem('petcare-mock-pets') ?? 'null') as { pets: { petId: number; deceasedOn: string | null }[] } | null;
    if (pets) {
      pets.pets.find((p) => p.petId === 3)!.deceasedOn = '2026-11-01';
      localStorage.setItem('petcare-mock-pets', JSON.stringify(pets));
      const dead = await failure(post(doctor, `/api/home-visit-appointments/${TODAY_ID}/start`));
      expect(ruleOf(dead)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-KB-07']);
    }
  });

  it('khách ngoài danh sách: lễ tân check-in lịch tại nhà qua POST /visits → 400 BR-KB-07; danh sách chờ check-in không có lịch tại nhà', async () => {
    const reception = await desk();
    await listOf(reception, '/api/home-visit-appointments'); // nạp mẫu
    patchAppointments((rows) => {
      const row = rows.find((r) => r.appointmentId === TODAY_ID)!;
      row.slotStart = hhmm();
    });
    const res = await failure(client.post('/api/visits', { petId: 3, appointmentId: TODAY_ID }, reception));
    expect(ruleOf(res)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-KB-07']);
    const arrivals = data<PageResponse<Appointment>>(await client.get('/api/appointments', { params: { checkInReady: true, date: today() }, ...reception }));
    expect(arrivals.content.some((a) => a.appointmentId === TODAY_ID)).toBe(false);
  });
});

// ─── #6 Không gặp được khách ───

describe('ghi nhận không gặp được khách (#6, UC51, BR-LH-14, BR-LH-09)', () => {
  it('BOOKED → NO_SHOW kèm ghi chú; báo khách có tài khoản; 409 khi ghi lần hai', async () => {
    const doctor = await vet();
    const res = await post(doctor, `/api/home-visit-appointments/${TODAY_ID}/mark-unreachable`, { note: '  Gọi ba lần không ai nghe  ' });
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã ghi nhận không gặp được khách');
    expect(data<HomeVisitAppointment>(res)).toMatchObject({ appointmentId: TODAY_ID, status: 'NO_SHOW', visitId: null });
    const mine = data<PageResponse<{ type: string; title: string }>>(await client.get('/api/me/notifications', { params: { unreadOnly: true }, ...(await customer()) }));
    expect(mine.content.filter((n) => n.type === 'HOME_VISIT_UNREACHABLE')).toMatchObject([{ title: expect.stringContaining('chưa gặp được bạn') }]);
    expect(await failure(post(doctor, `/api/home-visit-appointments/${TODAY_ID}/mark-unreachable`, { note: 'Lần hai' }))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
  });

  it('tính vào hạn chế đặt online ngay lúc ghi (ST05 bỏ qua lịch tại nhà): đủ 3 vi phạm trong 90 ngày thì khách bị hạn chế (BR-LH-09)', async () => {
    const reception = await desk();
    const doctor = await vet();
    const restriction = async () => data<RestrictionStatus>(await client.get('/api/customers/1/booking-restriction', reception)).restricted;
    await listOf(reception, '/api/home-visit-appointments'); // nạp mẫu
    patchAppointments((rows) => {
      rows.find((r) => r.appointmentId === SOON_ID)!.slotDate = today(); // đưa lịch #13 về hôm nay
      rows.find((r) => r.appointmentId === 1)!.slotDate = addDays(today(), 9); // lịch thường 11:00 hôm nay sẽ thành NO_SHOW nếu máy chạy sau 11:30: bỏ khỏi phép đếm cho kết quả không phụ thuộc giờ chạy
    });
    // Khách #1 còn đúng 1 vi phạm (hủy muộn lịch thường); hai lần không gặp được nữa là đủ ngưỡng 3.
    expect(await restriction()).toBe(false);
    await post(doctor, `/api/home-visit-appointments/${TODAY_ID}/mark-unreachable`, { note: 'Không ai ra' });
    expect(await restriction()).toBe(false);
    await post(doctor, `/api/home-visit-appointments/${SOON_ID}/mark-unreachable`, { note: 'Số điện thoại tắt máy' });
    expect(await restriction()).toBe(true);
    const online = await failure(client.post('/api/appointments', { petId: 3, branchId: 1, serviceId: 1, ...(await freeSlot(await customer())) }, await customer()));
    expect(ruleOf(online)).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-09']);
  });

  it('ST05 bỏ qua lịch tại nhà: lịch tại nhà quá giờ vẫn BOOKED, lịch thường quá giờ thành NO_SHOW', async () => {
    const reception = await desk();
    await listOf(reception, '/api/home-visit-appointments');
    const past = addDays(today(), -3);
    patchAppointments((rows) => {
      for (const id of [TODAY_ID, 1]) Object.assign(rows.find((r) => r.appointmentId === id)!, { slotDate: past, slotStart: '09:00' });
    });
    expect(listedFor(await listOf(reception, '/api/home-visit-appointments', { date: past, status: 'BOOKED' }))).toEqual([TODAY_ID]);
    expect(data<Appointment>(await client.get('/api/appointments/1', reception)).status).toBe('NO_SHOW');
  });

  it('thứ tự: path / body sai kiểu 400 → VET 403 → ghi chú > 300 400 → 404 → chi nhánh 403 → BR-LH-14 → 409', async () => {
    const doctor = await vet();
    const url = (id: number) => `/api/home-visit-appointments/${id}/mark-unreachable`;
    expect((await failure(post(doctor, '/api/home-visit-appointments/abc/mark-unreachable', { note: 'x' }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(post(await desk(), url(TODAY_ID), { note: [] }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post(url(TODAY_ID), undefined, doctor))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(post(await desk(), url(TODAY_ID), { note: 'x' }))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(post(doctor, url(9999), { note: 'x'.repeat(301) }))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(post(doctor, url(9999), { note: 'x' }))).status).toBe(404);
    expect((await failure(post(doctor, url(BRANCH2_ID), { note: 'x' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    // Lịch thường (mã 1) và ngoài ngày hẹn: 400 BR-LH-14, trước cả thiếu ghi chú.
    expect(ruleOf(await failure(post(doctor, url(1), { note: 'x' })))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-14']);
    expect(ruleOf(await failure(post(doctor, url(SOON_ID), { note: '' })))).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-14']);
    expect((await failure(post(doctor, url(SOON_ID), { note: 'x' }))).data.message).toMatch(/trong ngày hẹn/);
    for (const body of [{}, { note: '' }, { note: '   ' }, { note: null }]) {
      const res = await failure(post(doctor, url(TODAY_ID), body));
      expect([JSON.stringify(body), ...ruleOf(res)]).toEqual([JSON.stringify(body), 400, 'BUSINESS_RULE_VIOLATION', 'BR-LH-14']);
      expect(res.data.message).toMatch(/ghi chú/);
    }
    // Đã CANCELLED hôm nay (lịch #15 chuyển về hôm nay) → hợp lệ về ngày và ghi chú nên 409.
    await listOf(doctor, '/api/home-visit-appointments');
    patchAppointments((rows) => {
      rows.find((r) => r.appointmentId === CANCELLED_ID)!.slotDate = today();
    });
    expect(await failure(post(doctor, url(CANCELLED_ID), { note: 'x' }))).toMatchObject({ status: 409, data: { errorCode: 'INVALID_STATE_TRANSITION' } });
  });
});

// ─── Dùng chung với appointment-v1 (BR-LH-14) và GĐ1 không bị ảnh hưởng ───

describe('hủy / đổi giờ qua appointment-v1 và lịch thường', () => {
  it('GET /appointments không liệt kê lịch tại nhà và không lộ trường tại nhà; chi tiết / hủy theo mã vẫn chạy và cập nhật lịch tại nhà', async () => {
    const auth = await customer();
    await listOf(auth, '/api/me/home-visit-appointments');
    const normal = data<PageResponse<Appointment>>(await client.get('/api/appointments', { params: { size: 100 }, ...auth }));
    expect(normal.content.some((a) => a.appointmentId >= TODAY_ID)).toBe(false);
    expect(normal.totalElements).toBe(6); // 6 lịch thường của khách #1, như trước khi có lịch tại nhà
    const detail = data<Appointment>(await client.get(`/api/appointments/${SOON_ID}`, auth));
    expect(detail).toMatchObject({ appointmentId: SOON_ID, status: 'BOOKED', petName: 'Bim', serviceName: 'Khám tổng quát' });
    for (const key of ['isHomeVisit', 'homeAddress', 'homeContactPhone', 'homeFee', 'createdAt', 'unreachableNote', 'noShowAt']) expect(detail).not.toHaveProperty(key);

    const cancelled = await post(auth, `/api/appointments/${SOON_ID}/cancel`, { reason: 'Bé đã đỡ' });
    expect(cancelled.data.data).toMatchObject({ appointmentId: SOON_ID, status: 'CANCELLED', cancelSource: 'CUSTOMER' });
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${SOON_ID}`, auth)).status).toBe('CANCELLED');
    // Phí chỉ phát sinh khi thực hiện (BR-LH-14): hủy không đổi phí đã chốt.
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${SOON_ID}`, auth)).homeFee).toBe(100_000);
  });

  it('đổi giờ qua POST /appointments/{id}/reschedule: giữ mã, đếm lượt đổi, chiếm quota khung mới', async () => {
    const auth = await customer();
    await listOf(auth, '/api/me/home-visit-appointments');
    const slot = await freeSlot(auth, 9);
    const res = await post(auth, `/api/appointments/${SOON_ID}/reschedule`, slot);
    expect(res.data.data).toMatchObject({ appointmentId: SOON_ID, ...slot, rescheduleCount: 1 });
    expect(data<HomeVisitAppointment>(await client.get(`/api/home-visit-appointments/${SOON_ID}`, auth))).toMatchObject({ ...slot, status: 'BOOKED' });
  });
});

// ─── State lưu dạng cũ / thiếu trường ───

describe('state localStorage', () => {
  it('state lịch hẹn lưu từ trước T57 (không có homeSeeded, không có lịch tại nhà) không làm route nổ: nạp mẫu tại nhà một lần, mã nối tiếp, lịch thường giữ nguyên', async () => {
    localStorage.setItem(
      'petcare-mock-appointments',
      JSON.stringify({
        nextId: 5,
        nextCode: 5,
        nextRestrictionId: 1,
        appointments: [
          { appointmentId: 4, code: 'LH-261001-0004', customerId: 1, petId: 1, branchId: 1, serviceId: 1, serviceGroup: 'MEDICAL', slotDate: addDays(today(), 9), slotStart: '09:00', status: 'BOOKED', channel: 'ONLINE', bookedBy: 7, rescheduleCount: 0, lateCancel: false, cancelSource: null, cancelReason: null, cancelledAt: null, note: null, visitId: null },
        ],
        restrictions: [],
      }),
    );
    const auth = await customer();
    const mine = await listOf(auth, '/api/me/home-visit-appointments');
    expect(listedFor(mine)).toEqual([6, 5, 8, 7]); // 5 Tôm hôm nay · 6 Bim sau 2 ngày · 7 Mochi đã khám · 8 Bim đã hủy (mã nối tiếp nextId = 5)
    expect(JSON.parse(localStorage.getItem('petcare-mock-appointments')!)).toMatchObject({ homeSeeded: true, nextId: 11 });
    expect(data<PageResponse<Appointment>>(await client.get('/api/appointments', auth)).content.map((a) => a.appointmentId)).toEqual([4]);
    // Nạp lại không nhân đôi mẫu.
    expect((await listOf(auth, '/api/me/home-visit-appointments')).totalElements).toBe(4);
  });

  it('JSON hỏng: dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-appointments', 'không phải json');
    expect(listedFor(await listOf(await customer(), '/api/me/home-visit-appointments'))).toEqual([SOON_ID, TODAY_ID, CANCELLED_ID, DONE_ID]);
  });
});
