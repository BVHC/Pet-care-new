import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import type { AccountSummary, Role } from '../../types/auth';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';
import { visitRoutes } from './visit.mock';

// Giờ cố định: thứ Sáu 23/10/2026 10:40 (giờ Việt Nam), chi nhánh 1 đang mở cửa (T2–T7 08:00–12:00, 13:30–19:00).
// Dữ liệu mẫu (visit.mock.ts), chi nhánh 1, lễ tân #4 · VET #5 · CARETAKER #6 · quản lý #3:
//  - COMPLETED: #61 (Order #2), #58 (Order #3), #40 (Order #7) khớp Order VISIT của sales.mock; #2001 khớp lịch hẹn #4.
//  - Đang mở: #2101 Miu CẤP CỨU chờ chưa gán · #2002 Lu có lịch hẹn #10 đang khám (VET #5) · #2102 Tôm walk-in chờ (VET #5)
//    · #2103 Bim Thẩm mỹ chờ (CARETAKER #6) · #2104 Mochi Thẩm mỹ đang làm, người phụ trách #12 không còn (cần gán lại).
// Thứ tự hàng đợi (BR-TN-04): 2101 → 2002 → 2104 → 2102 → 2103.
// Lịch hẹn mẫu: #1 Mochi Khám BOOKED hôm nay 11:00 (trong cửa sổ check-in), #11 Lu BOOKED mai 10:00.
const NOW = new Date('2026-10-23T10:40:00+07:00');
const OPEN_QUEUE = [2101, 2002, 2104, 2102, 2103];

const VISIT_KEYS = [
  'appointmentId',
  'assigneeId',
  'assigneeName',
  'branchId',
  'calledAt',
  'cancelReason',
  'cancelledAt',
  'checkedInAt',
  'code',
  'completedAt',
  'customerId',
  'customerName',
  'needsReassign',
  'orderId',
  'petId',
  'petName',
  'priorityClass',
  'queueSortKey',
  'serviceGroup',
  'species',
  'status',
  'visitId',
];

let client: AxiosInstance;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  client = axios.create();
  installMockApi(client);
});
afterEach(() => vi.useRealTimers());

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const desk = () => as('reception@store1.vn');
const vet = () => as('doctor@store1.vn');
const caretaker = () => as('caretaker@store1.vn');
const manager = () => as('manager@store1.vn');
const chain = () => as('chuoi@petcare.vn');
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
const ruleOf = (res: AxiosResponse) => ruleIdOf(res.data.message);
const visitIds = (res: AxiosResponse) => res.data.data.map((v: { visitId: number }) => v.visitId);

describe('dữ liệu mẫu khớp sales.mock / appointment.mock', () => {
  it('Visit COMPLETED #61, #58, #40 khớp Order VISIT #2, #3, #7 (cùng khách, chi nhánh, người phụ trách, giờ)', async () => {
    const auth = await desk();
    for (const [visitId, orderId] of [[61, 2], [58, 3], [40, 7]]) {
      const visit = (await client.get(`/api/visits/${visitId}`, auth)).data.data;
      const order = (await client.get(`/api/orders/${orderId}`, auth)).data.data;
      expect(visit, `visit ${visitId}`).toMatchObject({ status: 'COMPLETED', orderId, customerId: order.customerId, branchId: order.branchId, assigneeId: 5, assigneeName: 'BS. Minh Anh', appointmentId: null });
      expect(order.visitId).toBe(visitId);
      expect(Date.parse(visit.checkedInAt)).toBe(Date.parse(order.createdAt));
      expect(Date.parse(visit.completedAt)).toBe(Date.parse(order.pendingAt));
      expect(Date.parse(visit.calledAt)).toBeGreaterThanOrEqual(Date.parse(visit.checkedInAt));
      expect(Date.parse(visit.calledAt)).toBeLessThanOrEqual(Date.parse(visit.completedAt));
    }
  });

  it('lịch hẹn #10 (CHECKED_IN, visitId 2002) và #4 (COMPLETED, visitId 2001) trỏ tới Visit có thật, cùng khách / thú / lịch hẹn', async () => {
    const auth = await desk();
    for (const [appointmentId, visitId, status] of [[10, 2002, 'IN_PROGRESS'], [4, 2001, 'COMPLETED']] as const) {
      const appointment = (await client.get(`/api/appointments/${appointmentId}`, auth)).data.data;
      expect(appointment.visitId).toBe(visitId);
      const visit = (await client.get(`/api/visits/${visitId}`, auth)).data.data;
      expect(visit).toMatchObject({ status, appointmentId, customerId: appointment.customerId, petId: appointment.petId, serviceGroup: appointment.serviceGroup });
    }
  });

  it('mỗi Visit đang mở có Order VISIT OPEN với đúng một dòng dịch vụ tự sinh, chủ dòng là người phụ trách (hoặc lễ tân khi chưa gán)', async () => {
    const auth = await desk();
    for (const [visitId, ownerId] of [[2101, 4], [2002, 5], [2102, 5], [2103, 6], [2104, 12]]) {
      const visit = (await client.get(`/api/visits/${visitId}`, auth)).data.data;
      const order = (await client.get(`/api/orders/${visit.orderId}`, auth)).data.data;
      expect(order, `order of ${visitId}`).toMatchObject({ source: 'VISIT', status: 'OPEN', visitId, customerId: visit.customerId });
      expect(order.lines).toHaveLength(1);
      expect(order.lines[0], `line of ${visitId}`).toMatchObject({ lineType: 'SERVICE', isAutoGenerated: true, quantity: 1, ownerAccountId: ownerId });
    }
  });
});

describe('hàng đợi chi nhánh (#2, UC45, BR-TN-04)', () => {
  it('mặc định chỉ lượt chưa kết thúc, đúng thứ tự cấp cứu → lịch hẹn → walk-in theo giờ check-in', async () => {
    const res = await client.get('/api/visits', await desk());
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('success');
    expect(visitIds(res)).toEqual(OPEN_QUEUE);
    expect(res.data.data.map((v: { priorityClass: string }) => v.priorityClass)).toEqual(['EMERGENCY', 'APPOINTMENT', 'WALK_IN', 'WALK_IN', 'WALK_IN']);
  });

  it('mỗi lượt có đủ 22 trường của hợp đồng, chưa có giá trị thì null; tên lấy từ hồ sơ khách / thú / nhân viên', async () => {
    const res = await client.get('/api/visits', await desk());
    const byId = Object.fromEntries(res.data.data.map((v: { visitId: number }) => [v.visitId, v]));
    for (const v of res.data.data) expect(Object.keys(v).sort()).toEqual(VISIT_KEYS);
    expect(byId[2101]).toMatchObject({
      code: expect.stringMatching(/^TN-2610-\d{5}$/),
      branchId: 1,
      customerName: 'Lê Thị Hoa',
      petName: 'Miu',
      species: 'CAT',
      appointmentId: null,
      serviceGroup: 'MEDICAL',
      status: 'WAITING',
      priorityClass: 'EMERGENCY',
      assigneeId: null,
      assigneeName: null,
      needsReassign: false,
      calledAt: null,
      completedAt: null,
      cancelledAt: null,
      cancelReason: null,
    });
    expect(byId[2002]).toMatchObject({ customerName: 'Lê Hoàng Long', petName: 'Lu', appointmentId: 10, assigneeId: 5, assigneeName: 'BS. Minh Anh', status: 'IN_PROGRESS' });
    expect(byId[2002].calledAt).not.toBeNull();
  });

  it('khóa sắp xếp: lịch hẹn theo giờ hẹn, cấp cứu / walk-in theo giờ check-in; thời điểm dạng Instant UTC', async () => {
    const byId = Object.fromEntries((await client.get('/api/visits', await desk())).data.data.map((v: { visitId: number }) => [v.visitId, v]));
    expect(byId[2002].queueSortKey).toBe('2026-10-23T01:30:00Z'); // hẹn 08:30 giờ Việt Nam
    expect(byId[2101].queueSortKey).toBe(byId[2101].checkedInAt);
    expect(byId[2103].queueSortKey).toBe(byId[2103].checkedInAt);
    expect(byId[2103].checkedInAt).toMatch(/^2026-10-23T\d{2}:\d{2}:\d{2}(\.\d{3}|\.\d{6})?Z$/);
  });

  it('bộ lọc: status, serviceGroup, assigneeId, needsReassign', async () => {
    const auth = await desk();
    expect(visitIds(await client.get('/api/visits?status=WAITING', auth))).toEqual([2101, 2102, 2103]);
    expect(visitIds(await client.get('/api/visits?status=IN_PROGRESS', auth))).toEqual([2002, 2104]);
    expect(visitIds(await client.get('/api/visits?serviceGroup=GROOMING', auth))).toEqual([2104, 2103]);
    expect(visitIds(await client.get('/api/visits?assigneeId=5', auth))).toEqual([2002, 2102]);
    expect(visitIds(await client.get('/api/visits?needsReassign=true', auth))).toEqual([2104]);
    expect(visitIds(await client.get('/api/visits?needsReassign=false', auth))).toEqual([2101, 2002, 2102, 2103]);
  });

  it('status COMPLETED / CANCELLED mà không gửi date: chỉ lượt check-in hôm nay (A1)', async () => {
    const auth = await desk();
    expect(visitIds(await client.get('/api/visits?status=COMPLETED', auth))).toEqual([61]);
    expect(visitIds(await client.get('/api/visits?status=CANCELLED', auth))).toEqual([]);
  });

  it('gửi date: mọi trạng thái của ngày check-in đó (giờ Việt Nam); ngày cũ tìm được lượt đã xong', async () => {
    const auth = await desk();
    expect(visitIds(await client.get('/api/visits?date=2026-10-23', auth))).toEqual([2101, 2002, 61, 2104, 2102, 2103]);
    expect(visitIds(await client.get('/api/visits?date=2026-10-10', auth))).toEqual([2001]);
    expect(visitIds(await client.get('/api/visits?date=2026-10-23&status=COMPLETED', auth))).toEqual([61]);
  });

  it('tham số sai kiểu → 400 MALFORMED_REQUEST theo thứ tự tham số của controller, trước cả kiểm vai trò', async () => {
    const auth = await desk();
    for (const [q, name] of [['status=BAD', 'status'], ['date=2026-13-45', 'date'], ['serviceGroup=BOARDING', 'serviceGroup'], ['assigneeId=abc', 'assigneeId'], ['needsReassign=maybe', 'needsReassign']]) {
      const res = await failure(client.get(`/api/visits?${q}`, auth));
      expect(res.status, q).toBe(400);
      expect(res.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: `Tham số '${name}' sai kiểu dữ liệu` });
    }
    expect((await failure(client.get('/api/visits?status=BAD', await admin()))).status).toBe(400);
  });

  it('A05–A08 xem được; SUPER_MANAGER và ADMIN → 403', async () => {
    for (const who of [desk, vet, caretaker, manager]) expect((await client.get('/api/visits', await who())).status).toBe(200);
    for (const who of [chain, admin]) expect((await failure(client.get('/api/visits', await who()))).status).toBe(403);
  });
});

describe('hàng đợi của tôi (#3, UC46)', () => {
  it('VET #5 thấy lượt WAITING / IN_PROGRESS gán cho mình theo thứ tự BR-TN-04; CARETAKER #6 chỉ thấy lượt của mình', async () => {
    expect(visitIds(await client.get('/api/me/queue', await vet()))).toEqual([2002, 2102]);
    expect(visitIds(await client.get('/api/me/queue', await caretaker()))).toEqual([2103]);
  });

  it('chỉ VET, CARETAKER; mọi vai trò khác → 403 (không 500)', async () => {
    for (const who of [desk, manager, chain, admin]) expect((await failure(client.get('/api/me/queue', await who()))).status).toBe(403);
  });
});

describe('chi tiết lượt (#4)', () => {
  it('A04–A08 xem được; ADMIN → 403', async () => {
    for (const who of [desk, vet, caretaker, manager, chain]) {
      const res = await client.get('/api/visits/2101', await who());
      expect(res.data.data.visitId).toBe(2101);
    }
    expect((await failure(client.get('/api/visits/2101', await admin()))).status).toBe(403);
  });

  it('không có → 404 theo mẫu của BE; mã sai kiểu → 400 trước cả vai trò', async () => {
    const missing = await failure(client.get('/api/visits/99999', await desk()));
    expect(missing.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy lượt tiếp nhận #99999' });
    const bad = await failure(client.get('/api/visits/abc', await admin()));
    expect(bad.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: "Tham số 'visitId' sai kiểu dữ liệu" });
  });

  it('nhân viên chi nhánh khác → 403 ACCESS_DENIED_SCOPE_MISMATCH, hàng đợi của họ không thấy lượt của chi nhánh 1; SUPER_MANAGER toàn chuỗi', async () => {
    const manager2 = callerOf('BRANCH_MANAGER', 9, 2);
    const detail = direct('GET /api/visits/:visitId', manager2, { params: { visitId: '2101' } });
    expect([detail.status, (detail.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect((direct('GET /api/visits', manager2).body as { data: unknown[] }).data).toEqual([]);
    expect((await client.get('/api/visits/2101', await chain())).data.data.branchId).toBe(1);
  });
});

// ─── Tiếp nhận (#1) ───

const at = (hhmm: string) => new Date(`2026-10-23T${hhmm}:00+07:00`);
const setNow = (d: Date) => vi.setSystemTime(d);
const checkIn = async (body: object, who: () => Promise<object> = desk) => client.post('/api/visits', body, await who());
const WINDOW_MESSAGE = 'Chỉ tiếp nhận lịch hẹn của hôm nay, sớm nhất 30 phút trước giờ hẹn và trễ nhất 30 phút sau giờ hẹn (BR-TN-02)';

/** Tài khoản nhân viên không đăng nhập được trong mock (chi nhánh 2 chỉ có quản lý), nên gọi thẳng handler với người gọi giả. */
const callerOf = (role: Role, id: number, branchId: number): AccountSummary => ({ id, email: `u${id}@store${branchId}.vn`, role, status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId });
const direct = (key: string, account: AccountSummary, over: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> } = {}) =>
  visitRoutes[key]({ body: over.body, account, params: over.params ?? {}, query: over.query ?? {} });
const desk2 = callerOf('RECEPTIONIST', 11, 2); // lễ tân chi nhánh 2 (DRAFT) của dữ liệu mẫu thu ngân

describe('tiếp nhận walk-in (#1, UC44)', () => {
  it('lễ tân tiếp nhận: 201, WAITING, WALK_IN, khóa sắp xếp = giờ check-in, chưa gán, mã TN-yyMM kế tiếp', async () => {
    const res = await checkIn({ petId: 3, serviceId: 1 });
    expect(res.status).toBe(201);
    expect(res.data.message).toBe('Đã tiếp nhận');
    const visit = res.data.data;
    expect(Object.keys(visit).sort()).toEqual(VISIT_KEYS);
    expect(visit).toMatchObject({
      branchId: 1,
      customerId: 1,
      customerName: 'Nguyễn Thu Trang',
      petId: 3,
      petName: 'Tôm',
      species: 'OTHER',
      appointmentId: null,
      serviceGroup: 'MEDICAL',
      status: 'WAITING',
      priorityClass: 'WALK_IN',
      queueSortKey: '2026-10-23T03:40:00Z',
      checkedInAt: '2026-10-23T03:40:00Z',
      assigneeId: null,
      assigneeName: null,
      needsReassign: false,
      calledAt: null,
    });
    expect(visit.code).toMatch(/^TN-2610-\d{5}$/);
    const next = (await checkIn({ petId: 1, serviceId: 3 })).data.data;
    expect(Number(next.code.slice(-5))).toBe(Number(visit.code.slice(-5)) + 1);
  });

  it('mở Order VISIT OPEN với một dòng dịch vụ tự sinh: tên và giá chụp lúc này, chủ dòng là lễ tân (chưa gán)', async () => {
    const visit = (await checkIn({ petId: 3, serviceId: 1 })).data.data;
    const order = (await client.get(`/api/orders/${visit.orderId}`, await desk())).data.data;
    expect(order).toMatchObject({ source: 'VISIT', status: 'OPEN', visitId: visit.visitId, customerId: 1, branchId: 1, totalAmount: 150_000 });
    expect(order.lines).toHaveLength(1);
    expect(order.lines[0]).toMatchObject({ lineType: 'SERVICE', serviceId: 1, description: 'Khám tổng quát', unitPrice: 150_000, quantity: 1, isAutoGenerated: true, addedBy: 4, ownerAccountId: 4, canEdit: false, canDelete: false });
  });

  it('dịch vụ Thẩm mỹ thì nhóm GROOMING; vào hàng đợi đúng chỗ (walk-in xếp sau lượt check-in trước đó)', async () => {
    const visit = (await checkIn({ petId: 1, serviceId: 3 })).data.data;
    expect(visit.serviceGroup).toBe('GROOMING');
    expect(visitIds(await client.get('/api/visits', await desk()))).toEqual([...OPEN_QUEUE, visit.visitId]);
  });

  it('cấp cứu (isEmergency) lên đầu hàng đợi, khóa sắp xếp = giờ check-in; tên trường sai `emergency` bị BE bỏ qua nên KHÔNG thành cấp cứu', async () => {
    const urgent = (await checkIn({ petId: 3, serviceId: 1, isEmergency: true })).data.data;
    expect(urgent).toMatchObject({ priorityClass: 'EMERGENCY', queueSortKey: '2026-10-23T03:40:00Z' });
    const wrongName = (await checkIn({ petId: 1, serviceId: 1, emergency: true })).data.data;
    expect(wrongName.priorityClass).toBe('WALK_IN');
    expect(visitIds(await client.get('/api/visits', await desk())).slice(0, 3)).toEqual([2101, urgent.visitId, 2002]);
  });

  it('BE không chặn thú đã có lượt mở: tiếp nhận lần nữa vẫn 201 (docs không có rule "một lượt mở mỗi thú")', async () => {
    const first = await checkIn({ petId: 3, serviceId: 1 });
    const again = await checkIn({ petId: 3, serviceId: 1 });
    expect([first.status, again.status]).toEqual([201, 201]);
    expect(again.data.data.visitId).not.toBe(first.data.data.visitId);
  });

  it('check-in khóa thú: có lượt mở thì không đánh dấu đã mất được (BR-KH-05, ghi nhận qua customer-pet.mock)', async () => {
    await client.post('/api/visits/2102/cancel', undefined, await desk()); // Tôm hết lượt mở của dữ liệu mẫu
    await checkIn({ petId: 3, serviceId: 1 });
    const res = await failure(client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-23', confirmed: true }, await desk()));
    expect(res.status).toBe(400);
    expect(ruleOf(res)).toBe('BR-KH-05');
  });
});

describe('check-in lịch hẹn (#1, BR-TN-02, BR-TN-03, BR-LH-08)', () => {
  it('lịch #1 (Mochi, hẹn 11:00, đang 10:40): APPOINTMENT, khóa = giờ hẹn, lịch → CHECKED_IN kèm visitId; serviceId gửi kèm bị bỏ qua', async () => {
    const res = await checkIn({ petId: 1, appointmentId: 1, serviceId: 3 });
    expect(res.status).toBe(201);
    const visit = res.data.data;
    expect(visit).toMatchObject({ appointmentId: 1, serviceGroup: 'MEDICAL', priorityClass: 'APPOINTMENT', queueSortKey: '2026-10-23T04:00:00Z', checkedInAt: '2026-10-23T03:40:00Z' });
    const appointment = (await client.get('/api/appointments/1', await desk())).data.data;
    expect(appointment).toMatchObject({ status: 'CHECKED_IN', visitId: visit.visitId });
    const order = (await client.get(`/api/orders/${visit.orderId}`, await desk())).data.data;
    expect(order.lines[0]).toMatchObject({ serviceId: 1, description: 'Khám tổng quát' });
  });

  it('danh sách chờ check-in (checkInReady) không còn lịch vừa tiếp nhận', async () => {
    const ready = async () => (await client.get('/api/appointments', { params: { checkInReady: true, status: 'BOOKED' }, ...(await desk()) })).data.data.content.map((a: { appointmentId: number }) => a.appointmentId);
    expect(await ready()).toEqual([1]);
    await checkIn({ petId: 1, appointmentId: 1 });
    expect(await ready()).toEqual([]);
  });

  it('cửa sổ sớm: đúng 30 phút trước giờ hẹn là được, kém 1 giây thì 400 BR-TN-02', async () => {
    setNow(new Date(at('10:30').getTime() - 1000));
    const early = await failure(checkIn({ petId: 1, appointmentId: 1 }));
    expect(early.status).toBe(400);
    expect(early.data.message).toBe(WINDOW_MESSAGE);
    setNow(at('10:30'));
    expect((await checkIn({ petId: 1, appointmentId: 1 })).status).toBe(201);
  });

  it('trễ tối đa 15 phút giữ ưu tiên lịch hẹn (biên tính là giữ); trễ hơn xếp như walk-in nhưng vẫn gắn lịch hẹn, khóa = giờ check-in', async () => {
    setNow(at('11:15'));
    const kept = (await checkIn({ petId: 1, appointmentId: 1 })).data.data;
    expect(kept).toMatchObject({ priorityClass: 'APPOINTMENT', queueSortKey: '2026-10-23T04:00:00Z' });
    localStorage.clear();
    setNow(new Date(at('11:15').getTime() + 1000));
    const late = (await checkIn({ petId: 1, appointmentId: 1 })).data.data;
    expect(late).toMatchObject({ appointmentId: 1, priorityClass: 'WALK_IN', queueSortKey: '2026-10-23T04:15:01Z', checkedInAt: '2026-10-23T04:15:01Z' });
  });

  it('trễ đúng 30 phút còn check-in được (xếp walk-in); quá 30 phút lịch đã NO_SHOW nên 409 (BE thật cũng 409 sau tick ST05 mỗi 5 phút, đã dò lúc 16:01; trong ≤ 5 phút trước tick đó BE trả 400 cửa sổ)', async () => {
    setNow(at('11:30'));
    const lastChance = (await checkIn({ petId: 1, appointmentId: 1 })).data.data;
    expect(lastChance.priorityClass).toBe('WALK_IN');
    localStorage.clear();
    setNow(new Date(at('11:30').getTime() + 1000));
    const res = await failure(checkIn({ petId: 1, appointmentId: 1 }));
    expect(res.status).toBe(409);
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái lịch hẹn từ NO_SHOW sang CHECKED_IN' });
  });

  it('lịch hẹn không phải hôm nay → 400 BR-TN-02 (cùng thông điệp cửa sổ)', async () => {
    // Lịch #11 (Lu) hẹn mai 10:00.
    const res = await failure(checkIn({ petId: 4, appointmentId: 11 }));
    expect(res.status).toBe(400);
    expect(res.data.message).toBe(WINDOW_MESSAGE);
  });

  it('cấp cứu kèm lịch hẹn xếp EMERGENCY, khóa = giờ check-in (không phải giờ hẹn)', async () => {
    const visit = (await checkIn({ petId: 1, appointmentId: 1, isEmergency: true })).data.data;
    expect(visit).toMatchObject({ appointmentId: 1, priorityClass: 'EMERGENCY', queueSortKey: '2026-10-23T03:40:00Z' });
  });

  it('lịch đã CHECKED_IN / COMPLETED / CANCELLED / NO_SHOW → 409 theo trạng thái của lịch (tiếp nhận như walk-in thì bỏ appointmentId)', async () => {
    for (const [petId, appointmentId, from] of [[4, 10, 'CHECKED_IN'], [1, 4, 'COMPLETED'], [2, 5, 'CANCELLED'], [4, 7, 'NO_SHOW']] as const) {
      const res = await failure(checkIn({ petId, appointmentId }));
      expect(res.status, from).toBe(409);
      expect(res.data.message).toBe(`Không thể chuyển trạng thái lịch hẹn từ ${from} sang CHECKED_IN`);
    }
    expect((await checkIn({ petId: 4, serviceId: 1 })).status).toBe(201);
  });

  it('lịch của chi nhánh khác → 400 BR-TN-02 ngay sau 404, TRƯỚC mọi guard khác (không lộ thú / dịch vụ của lịch), kể cả khi chi nhánh người gọi chưa ACTIVE', async () => {
    const res = direct('POST /api/visits', desk2, { body: { petId: 1, appointmentId: 1 } });
    expect(res.status).toBe(400);
    expect((res.body as { message: string }).message).toBe('Lịch hẹn thuộc chi nhánh khác (BR-TN-02)');
  });

  it('thú không khớp lịch hẹn → 400 BR-TN-01', async () => {
    const res = await failure(checkIn({ petId: 3, appointmentId: 1 }));
    expect(res.status).toBe(400);
    expect(res.data.message).toBe('Thú cưng không khớp lịch hẹn (BR-TN-01)');
  });
});

describe('tiếp nhận: thứ tự kiểm và rule (#1)', () => {
  it('404 theo thứ tự thú → lịch hẹn → dịch vụ, mỗi cái đúng một thông điệp', async () => {
    const msg = async (body: object) => (await failure(checkIn(body))).data.message;
    expect(await msg({ petId: 99999, appointmentId: 99999, serviceId: 99999 })).toBe('Không tìm thấy thú cưng #99999');
    expect(await msg({ petId: 1, appointmentId: 99999, serviceId: 99999 })).toBe('Không tìm thấy lịch hẹn #99999');
    expect(await msg({ petId: 1, serviceId: 99999 })).toBe('Không tìm thấy dịch vụ #99999');
  });

  it('walk-in thiếu dịch vụ → 400 BR-TN-01', async () => {
    const res = await failure(checkIn({ petId: 1 }));
    expect(res.status).toBe(400);
    expect(res.data.message).toBe('Khách không hẹn trước phải chọn dịch vụ (BR-TN-01)');
  });

  it('thú đã mất → 400 BR-TN-01 (không phải BR-KH-05), không tạo lượt', async () => {
    await client.post('/api/visits/2102/cancel', undefined, await desk()); // Tôm còn lượt mở nên chưa đánh dấu mất được
    await client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-23', confirmed: true }, await desk());
    const res = await failure(checkIn({ petId: 3, serviceId: 1 }));
    expect(res.status).toBe(400);
    expect(res.data.message).toBe('Thú cưng đã mất, không tiếp nhận được (BR-TN-01)');
    expect(visitIds(await client.get('/api/visits', await desk()))).toEqual(OPEN_QUEUE.filter((id) => id !== 2102));
  });

  it('chi nhánh người gọi chưa ACTIVE → 400 BR-CN-01', async () => {
    const res = direct('POST /api/visits', desk2, { body: { petId: 1, serviceId: 1 } });
    expect(res.status).toBe(400);
    expect((res.body as { message: string }).message).toBe('Chi nhánh chưa hoạt động, chưa tiếp nhận được (BR-CN-01)');
  });

  it('walk-in: dịch vụ không thuộc MEDICAL / GROOMING hoặc đang tắt tại chi nhánh → 400 BR-LH-01', async () => {
    const message = 'Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)';
    const boarding = await failure(checkIn({ petId: 1, serviceId: 5 }));
    expect([boarding.status, boarding.data.message]).toEqual([400, message]);
    await client.put('/api/branches/1/services/4', { enabled: false }, await manager());
    const disabled = await failure(checkIn({ petId: 1, serviceId: 4 }));
    expect([disabled.status, disabled.data.message]).toEqual([400, message]);
  });

  it('lịch hẹn mà dịch vụ bị tắt tại chi nhánh sau khi đặt vẫn tiếp nhận được (A12)', async () => {
    await client.put('/api/branches/1/services/1', { enabled: false }, await manager());
    expect((await checkIn({ petId: 1, appointmentId: 1 })).status).toBe(201);
  });
});

describe('tiếp nhận: ngoài giờ mở cửa (BR-CN-05)', () => {
  it('nghỉ trưa, chi nhánh có cờ: chỉ nhận ca cấp cứu', async () => {
    setNow(at('12:30'));
    const res = await failure(checkIn({ petId: 3, serviceId: 1 }));
    expect(res.status).toBe(400);
    expect(res.data.message).toBe('Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05)');
    const urgent = await checkIn({ petId: 3, serviceId: 1, isEmergency: true });
    expect(urgent.status).toBe(201);
    expect(urgent.data.data.priorityClass).toBe('EMERGENCY');
  });

  it('chi nhánh không bật cờ: ngoài giờ không nhận gì, kể cả cấp cứu', async () => {
    setNow(at('12:30'));
    await client.patch('/api/branches/1', { acceptsAfterHoursEmergency: false }, await chain());
    for (const isEmergency of [false, true]) {
      const res = await failure(checkIn({ petId: 3, serviceId: 1, isEmergency }));
      expect(res.status).toBe(400);
      expect(res.data.message).toBe('Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)');
    }
  });

  it('giờ mở cửa áp cả cho lịch hẹn đến sớm trong cửa sổ (A9): lịch 13:30 mà 13:10 chưa mở lại', async () => {
    const booked = await client.post('/api/appointments', { petId: 4, branchId: 1, serviceId: 3, slotDate: '2026-10-23', slotStart: '13:30' }, await desk());
    setNow(at('13:10'));
    const res = await failure(checkIn({ petId: 4, appointmentId: booked.data.data.appointmentId }));
    expect(res.data.message).toBe('Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05)');
    setNow(at('13:30'));
    expect((await checkIn({ petId: 4, appointmentId: booked.data.data.appointmentId })).status).toBe(201);
  });
});

describe('tiếp nhận: dữ liệu vào và quyền', () => {
  it('đọc JSON và Bean Validation chạy trước kiểm vai trò (400 trước 403)', async () => {
    const noBody = await failure(client.post('/api/visits', undefined, await vet()));
    expect(noBody.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu' });
    const empty = await failure(checkIn({}, vet));
    expect(empty.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'petId: Thiếu thú cưng' });
    const bad = await failure(checkIn({ petId: 0, appointmentId: -2, serviceId: 0 }));
    expect(bad.data.message).toBe('petId: Mã thú cưng không hợp lệ; appointmentId: Mã lịch hẹn không hợp lệ; serviceId: Mã dịch vụ không hợp lệ');
    expect((await failure(checkIn({ petId: 3.5, serviceId: 1 }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(checkIn({ petId: 3, serviceId: 1, isEmergency: 'yes' }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('chỉ lễ tân: VET, CARETAKER, BRANCH_MANAGER, SUPER_MANAGER, ADMIN → 403 (không 500)', async () => {
    for (const who of [vet, caretaker, manager, chain, admin]) expect((await failure(checkIn({ petId: 3, serviceId: 1 }, who))).status).toBe(403);
  });
});

// ─── Điều phối: gán, sắp lại, cấp cứu, gọi, hủy, gán lại (#5–#9, #11) ───

let staffSeq = 0;
beforeEach(() => {
  staffSeq = 0;
});
/** Nhân viên tạo lúc chạy (SM tạo): tìm được qua danh bạ nhưng không đăng nhập được trong mock. */
async function makeStaff(role: 'VET' | 'CARETAKER', branchId = 1): Promise<number> {
  staffSeq += 1;
  const res = await client.post('/api/staff', { email: `nv${staffSeq}.${role.toLowerCase()}@new.vn`, phone: '0900000077', fullName: `Nhân viên ${staffSeq}`, role, branchId }, await chain());
  return res.data.data.accountId;
}
/** Sửa thẳng một dòng trong kho của mock (dựng tình huống mà route không tạo được, vd Lượt của chi nhánh khác). */
async function patchVisit(visitId: number, patch: Record<string, unknown>) {
  await client.get('/api/visits', await desk()); // bảo đảm đã nạp dữ liệu mẫu
  const state = JSON.parse(localStorage.getItem('petcare-mock-visit')!);
  Object.assign(state.visits.find((v: { visitId: number }) => v.visitId === visitId), patch);
  localStorage.setItem('petcare-mock-visit', JSON.stringify(state));
}
const post = async (path: string, body: object | undefined, who: () => Promise<object> = desk) => client.post(path, body, await who());
const assign = (visitId: number, assigneeId: number, who?: () => Promise<object>) => post(`/api/visits/${visitId}/assign`, { assigneeId }, who);
const queueOf = async (who: () => Promise<object> = desk) => visitIds(await client.get('/api/visits', await who()));
const ROLE_ERROR = 'Lượt Khám/Tiêm phải gán bác sĩ thú y; lượt Thẩm mỹ phải gán nhân viên chăm sóc (BR-TN-05)';
const NOT_ACTIVE = 'Nhân viên không hoạt động tại chi nhánh này (BR-TN-05)';

describe('gán nhân viên (#5, UC45, BR-TN-05, BR-TN-06)', () => {
  it('lễ tân gán VET cho lượt chưa gán: 200, vẫn WAITING, vị trí hàng đợi không đổi, kèm cờ offline', async () => {
    const res = await assign(2101, 5);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã gán nhân viên');
    expect(Object.keys(res.data.data).sort()).toEqual(['assigneeOffline', 'visit']);
    expect(res.data.data).toMatchObject({ assigneeOffline: false, visit: { visitId: 2101, status: 'WAITING', assigneeId: 5, assigneeName: 'BS. Minh Anh' } });
    expect(await queueOf()).toEqual(OPEN_QUEUE);
  });

  it('dòng dịch vụ tự sinh chuyển quyền cho người được gán, và người đó thêm được dịch vụ vào Order (BR-BH-02)', async () => {
    const visit = (await assign(2101, 5)).data.data.visit;
    const order = (await client.get(`/api/orders/${visit.orderId}`, await desk())).data.data;
    expect(order.lines[0]).toMatchObject({ isAutoGenerated: true, ownerAccountId: 5 });
    const added = await client.post(`/api/orders/${visit.orderId}/lines`, { lineType: 'SERVICE', serviceId: 2, quantity: 1 }, await vet());
    expect(added.status).toBe(201);
  });

  it('gán lại lượt chưa gọi cho người khác bất kỳ lúc nào: dòng tự sinh và dòng người cũ đã thêm chuyển cho người mới (BR-TN-08)', async () => {
    await assign(2101, 5);
    const visit = (await client.get('/api/visits/2101', await desk())).data.data;
    await client.post(`/api/orders/${visit.orderId}/lines`, { lineType: 'SERVICE', serviceId: 2, quantity: 1 }, await vet());
    const other = await makeStaff('VET');
    const res = await assign(2101, other);
    expect(res.data.data.visit.assigneeId).toBe(other);
    const lines = (await client.get(`/api/orders/${visit.orderId}`, await desk())).data.data.lines;
    expect(lines.map((l: { ownerAccountId: number }) => l.ownerAccountId)).toEqual([other, other]);
  });

  it('gán đúng người đang được gán: 200, không đổi gì', async () => {
    const before = (await assign(2102, 5)).data.data;
    const again = await assign(2102, 5);
    expect(again.status).toBe(200);
    expect(again.data.data).toEqual(before);
  });

  it('người offline vẫn gán được, chỉ cảnh báo (BR-TN-06): assigneeOffline = true', async () => {
    const { setStaffOnline } = await import('./identity-admin.mock');
    setStaffOnline(5, false);
    const res = await assign(2101, 5);
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ assigneeOffline: true, visit: { assigneeId: 5 } });
  });

  it('sai chức vụ → 400 BR-TN-05 (MEDICAL cần VET, GROOMING cần CARETAKER; lễ tân, quản lý cũng không)', async () => {
    for (const [visitId, assigneeId] of [[2101, 6], [2103, 5], [2101, 4], [2101, 3]]) {
      const res = await failure(assign(visitId, assigneeId));
      expect(res.status, `${visitId}←${assigneeId}`).toBe(400);
      expect(res.data.message).toBe(ROLE_ERROR);
    }
  });

  it('không có / khác chi nhánh / bị khóa / đã vô hiệu hóa → 400 BR-TN-05 "không hoạt động tại chi nhánh này"', async () => {
    const otherBranch = await makeStaff('VET', 2);
    const locked = await makeStaff('VET');
    await client.post(`/api/accounts/${locked}/lock`, { reason: 'Thử khóa' }, await admin());
    const disabled = await makeStaff('VET');
    await client.post(`/api/staff/${disabled}/disable`, {}, await chain());
    for (const assigneeId of [99999, otherBranch, locked, disabled]) {
      const res = await failure(assign(2101, assigneeId));
      expect([res.status, res.data.message], String(assigneeId)).toEqual([400, NOT_ACTIVE]);
    }
  });

  it('400 BR-TN-05 đứng trước 409: lượt đã hủy + sai chức vụ → 400, + đúng người → 409; lượt đã gọi → 409', async () => {
    await post('/api/visits/2101/cancel', undefined);
    expect((await failure(assign(2101, 6))).status).toBe(400);
    const cancelled = await failure(assign(2101, 5));
    expect(cancelled.status).toBe(409);
    expect(cancelled.data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ CANCELLED sang WAITING');
    const called = await failure(assign(2002, 5));
    expect(called.data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang WAITING');
  });

  it('body sai → 400 trước cả vai trò; thiếu / không dương / sai kiểu', async () => {
    const noBody = await failure(post('/api/visits/2101/assign', undefined, vet));
    expect(noBody.data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(post('/api/visits/2101/assign', {}, vet))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'assigneeId: Thiếu nhân viên được gán' });
    expect((await failure(assign(2101, -1))).data.message).toBe('assigneeId: Mã nhân viên không hợp lệ');
    expect((await failure(post('/api/visits/2101/assign', { assigneeId: 'x' }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('chỉ lễ tân (403 cho còn lại); 404 theo mẫu; lễ tân chi nhánh khác → 403 phạm vi', async () => {
    for (const who of [vet, caretaker, manager, chain, admin]) expect((await failure(assign(2101, 5, who))).status).toBe(403);
    expect((await failure(assign(99999, 5))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy lượt tiếp nhận #99999' });
    const other = direct('POST /api/visits/:visitId/assign', desk2, { body: { assigneeId: 5 }, params: { visitId: '2101' } });
    expect(other.status).toBe(403);
    expect((other.body as { errorCode: string }).errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
  });
});

describe('xác định ca cấp cứu (#7, UC47)', () => {
  it('lễ tân đưa lượt WAITING lên nhóm cấp cứu: khóa sắp xếp = lúc đánh dấu, xếp sau ca cấp cứu đến trước', async () => {
    setNow(at('10:45'));
    const res = await post('/api/visits/2102/mark-emergency', undefined);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã xác định ca cấp cứu');
    expect(res.data.data).toMatchObject({ visitId: 2102, priorityClass: 'EMERGENCY', queueSortKey: '2026-10-23T03:45:00Z', status: 'WAITING' });
    expect(await queueOf()).toEqual([2101, 2102, 2002, 2104, 2103]);
  });

  it('VET cũng đánh dấu được (A07); CARETAKER, quản lý, quản lý chuỗi, admin → 403', async () => {
    expect((await post('/api/visits/2102/mark-emergency', undefined, vet)).status).toBe(200);
    for (const who of [caretaker, manager, chain, admin]) expect((await failure(post('/api/visits/2103/mark-emergency', undefined, who))).status).toBe(403);
  });

  it('đã là cấp cứu → 409 theo hạng ưu tiên; đã gọi / đã xong → 409 theo trạng thái (từ … sang WAITING)', async () => {
    const already = await failure(post('/api/visits/2101/mark-emergency', undefined));
    expect(already.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái hạng ưu tiên của lượt từ EMERGENCY sang EMERGENCY' });
    expect((await failure(post('/api/visits/2002/mark-emergency', undefined))).data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang WAITING');
    expect((await failure(post('/api/visits/61/mark-emergency', undefined))).data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang WAITING');
  });

  it('404, lễ tân chi nhánh khác → 403 phạm vi, mã sai kiểu → 400 trước vai trò', async () => {
    expect((await failure(post('/api/visits/99999/mark-emergency', undefined))).status).toBe(404);
    expect(direct('POST /api/visits/:visitId/mark-emergency', desk2, { params: { visitId: '2102' } }).status).toBe(403);
    expect((await failure(post('/api/visits/abc/mark-emergency', undefined, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('sắp lại hàng đợi (#6, BR-TN-04, A3)', () => {
  const move = (visitId: number, placeBeforeVisitId: number, who?: () => Promise<object>) => post(`/api/visits/${visitId}/move`, { placeBeforeVisitId }, who);
  const keyOf = (res: AxiosResponse, visitId: number) => res.data.data.find((v: { visitId: number }) => v.visitId === visitId).queueSortKey;

  it('đặt lượt ngay trước lượt đích: nhận khóa đích − 1 µs, trả cả hàng đợi chưa kết thúc', async () => {
    const res = await move(2103, 2102);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã sắp lại hàng đợi');
    expect(visitIds(res)).toEqual([2101, 2002, 2104, 2103, 2102]);
    expect(keyOf(res, 2102)).toBe('2026-10-23T03:10:00Z');
    expect(keyOf(res, 2103)).toBe('2026-10-23T03:09:59.999999Z');
  });

  it('nhận luôn hạng của lượt đích: walk-in đặt trước lượt có lịch hẹn thì thành APPOINTMENT', async () => {
    const appointment = (await checkIn({ petId: 1, appointmentId: 1 })).data.data;
    const res = await move(2103, appointment.visitId);
    const moved = res.data.data.find((v: { visitId: number }) => v.visitId === 2103);
    expect(moved.priorityClass).toBe('APPOINTMENT');
    expect(visitIds(res).indexOf(2103)).toBe(visitIds(res).indexOf(appointment.visitId) - 1);
  });

  it('đã đứng ngay trước lượt đích: 200, không đổi gì', async () => {
    const before = await queueOf();
    const res = await move(2102, 2103);
    expect(res.status).toBe(200);
    expect(visitIds(res)).toEqual(before);
    expect(keyOf(res, 2102)).toBe('2026-10-23T03:10:00Z');
  });

  it('các lượt trùng khóa (cùng giờ check-in) dồn +1 µs để giữ thứ tự: c đặt trước b thì a, c, b', async () => {
    const [a, b, c] = [(await checkIn({ petId: 1, serviceId: 1 })).data.data, (await checkIn({ petId: 2, serviceId: 3 })).data.data, (await checkIn({ petId: 3, serviceId: 1 })).data.data];
    expect([a.queueSortKey, b.queueSortKey, c.queueSortKey]).toEqual(Array(3).fill('2026-10-23T03:40:00Z'));
    const res = await move(c.visitId, b.visitId);
    expect(visitIds(res).slice(-3)).toEqual([a.visitId, c.visitId, b.visitId]);
    expect([keyOf(res, a.visitId), keyOf(res, c.visitId), keyOf(res, b.visitId)]).toEqual(['2026-10-23T03:40:00Z', '2026-10-23T03:40:00.000001Z', '2026-10-23T03:40:00.000002Z']);
  });

  it('thứ tự lỗi: chính nó 400 → đích 404 → ranh giới cấp cứu 400 → lượt nguồn 409 → lượt đích 409', async () => {
    const self = await failure(move(2103, 2103));
    expect([self.status, self.data.message]).toEqual([400, 'Không đặt lượt trước chính nó được (BR-TN-04)']);
    expect((await failure(move(2103, 99999))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy lượt tiếp nhận #99999' });
    const boundary = 'Ca cấp cứu chỉ sắp trong nhóm cấp cứu; muốn đưa lượt lên nhóm cấp cứu hãy xác định ca cấp cứu (BR-TN-04)';
    expect((await failure(move(2103, 2101))).data.message).toBe(boundary);
    expect((await failure(move(2101, 2103))).data.message).toBe(boundary);
    // Lượt đang làm (IN_PROGRESS, walk-in) đặt trước ca cấp cứu: vượt ranh giới (400) đứng trước kiểm trạng thái (409).
    expect((await failure(move(2104, 2101))).status).toBe(400);
    const source = await failure(move(2104, 2103));
    expect([source.status, source.data.message]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang WAITING']);
    const target = await failure(move(2103, 2104));
    expect([target.status, target.data.message]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang WAITING']);
  });

  it('ca cấp cứu sắp được giữa các ca cấp cứu', async () => {
    const second = (await checkIn({ petId: 3, serviceId: 1, isEmergency: true })).data.data;
    const res = await move(second.visitId, 2101);
    expect(visitIds(res).slice(0, 2)).toEqual([second.visitId, 2101]);
  });

  it('lượt đích thuộc chi nhánh khác → 400 BR-TN-04; lượt nguồn của chi nhánh khác → 403', async () => {
    await patchVisit(2102, { branchId: 2 });
    const target = await failure(move(2103, 2102));
    expect([target.status, target.data.message]).toEqual([400, 'Lượt đích thuộc chi nhánh khác (BR-TN-04)']);
    expect((await failure(move(2102, 2103))).status).toBe(403);
  });

  it('body sai → 400 trước vai trò; chỉ lễ tân', async () => {
    expect((await failure(post('/api/visits/2103/move', {}, vet))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'placeBeforeVisitId: Thiếu lượt đích' });
    expect((await failure(post('/api/visits/2103/move', { placeBeforeVisitId: 0 }))).data.message).toBe('placeBeforeVisitId: Mã lượt đích không hợp lệ');
    for (const who of [vet, caretaker, manager, chain, admin]) expect((await failure(move(2103, 2102, who))).status).toBe(403);
    expect((await failure(move(99999, 2102))).status).toBe(404);
  });
});

describe('gọi lượt (#8, UC46, BR-TN-05, BR-TN-09)', () => {
  const call = (visitId: number, who: () => Promise<object> = vet) => post(`/api/visits/${visitId}/call`, undefined, who);

  it('người được gán gọi lượt: WAITING → IN_PROGRESS, calledAt = lúc gọi, hiện trong hàng đợi của tôi', async () => {
    setNow(at('10:50'));
    const res = await call(2102);
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã gọi lượt');
    expect(res.data.data).toMatchObject({ visitId: 2102, status: 'IN_PROGRESS', calledAt: '2026-10-23T03:50:00Z', assigneeId: 5 });
    const mine = (await client.get('/api/me/queue', await vet())).data.data;
    expect(mine.map((v: { visitId: number; status: string }) => [v.visitId, v.status])).toEqual([[2002, 'IN_PROGRESS'], [2102, 'IN_PROGRESS']]);
    expect(((await call(2103, caretaker)).data.data as { status: string }).status).toBe('IN_PROGRESS');
  });

  it('lượt chưa gán, hoặc gán cho người khác → 403 ACCESS_DENIED_SCOPE_MISMATCH (BR-TN-05, 09)', async () => {
    for (const [visitId, who] of [[2101, vet], [2103, vet], [2102, caretaker]] as const) {
      const res = await failure(call(visitId, who));
      expect([res.status, res.data.errorCode, res.data.message], String(visitId)).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này']);
    }
  });

  it('gọi lần hai hoặc lượt đã xong / đã hủy → 409 theo trạng thái lượt', async () => {
    const twice = await failure(call(2002));
    expect([twice.status, twice.data.message]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang IN_PROGRESS']);
    expect((await failure(call(61))).data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');
  });

  it('người gọi không còn đủ điều kiện (bị khóa) → 400 BR-TN-05, kiểm sau 403 người được gán', async () => {
    const other = await makeStaff('VET');
    await assign(2101, other);
    await client.post(`/api/accounts/${other}/lock`, { reason: 'Thử khóa' }, await admin());
    const res = direct('POST /api/visits/:visitId/call', callerOf('VET', other, 1), { params: { visitId: '2101' } });
    expect(res.status).toBe(400);
    expect((res.body as { message: string }).message).toBe(NOT_ACTIVE);
  });

  it('chỉ VET, CARETAKER (403 còn lại, không 500); 404; chi nhánh khác → 403; mã sai kiểu → 400 trước vai trò', async () => {
    for (const who of [desk, manager, chain, admin]) expect((await failure(call(2102, who))).status).toBe(403);
    expect((await failure(call(99999))).data.message).toBe('Không tìm thấy lượt tiếp nhận #99999');
    expect(direct('POST /api/visits/:visitId/call', callerOf('VET', 5, 2), { params: { visitId: '2102' } }).status).toBe(403);
    expect((await failure(post('/api/visits/abc/call', undefined, admin))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('hủy lượt khách bỏ về (#11, BR-TN-07)', () => {
  const cancel = (visitId: number, body?: object, who?: () => Promise<object>) => post(`/api/visits/${visitId}/cancel`, body, who);

  it('lễ tân hủy lượt WAITING: CANCELLED, lý do bỏ khoảng trắng hai đầu; không còn trong hàng đợi mặc định', async () => {
    const res = await cancel(2101, { reason: '  Khách bận   ' });
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã hủy lượt');
    expect(res.data.data).toMatchObject({ status: 'CANCELLED', cancelReason: 'Khách bận', cancelledAt: '2026-10-23T03:40:00Z', assigneeId: null });
    expect(await queueOf()).toEqual(OPEN_QUEUE.filter((id) => id !== 2101));
    expect(visitIds(await client.get('/api/visits?status=CANCELLED', await desk()))).toEqual([2101]);
  });

  it('không gửi body, body {} hoặc lý do toàn khoảng trắng: hủy không lý do (A4)', async () => {
    expect((await cancel(2101)).data.data.cancelReason).toBeNull();
    expect((await cancel(2102, {})).data.data.cancelReason).toBeNull();
    expect((await cancel(2103, { reason: '   ' })).data.data.cancelReason).toBeNull();
  });

  it('Order của lượt → CANCELLED (VISIT_CANCELLED) kèm dòng tự sinh giữ làm lịch sử, không lý do', async () => {
    const visit = (await client.get('/api/visits/2101', await desk())).data.data;
    await cancel(2101);
    const order = (await client.get(`/api/orders/${visit.orderId}`, await desk())).data.data;
    expect(order).toMatchObject({ status: 'CANCELLED', cancelType: 'VISIT_CANCELLED', cancelReason: null, cancelledAt: '2026-10-23T03:40:00Z' });
    expect(order.lines).toHaveLength(1);
  });

  it('lượt có lịch hẹn: lịch → CANCELLED nguồn STAFF, KHÔNG lý do (lý do của lượt không chép sang), không tính hủy muộn dù sát giờ, visitId giữ nguyên', async () => {
    const visit = (await checkIn({ petId: 1, appointmentId: 1 })).data.data;
    await cancel(visit.visitId, { reason: 'Khách đổi ý' });
    const appointment = (await client.get('/api/appointments/1', await desk())).data.data;
    expect(appointment).toMatchObject({ status: 'CANCELLED', cancelSource: 'STAFF', cancelReason: null, lateCancel: false, cancelledAt: '2026-10-23T03:40:00Z', visitId: visit.visitId });
    const restriction = (await client.get('/api/customers/1/booking-restriction', await desk())).data.data;
    expect(restriction.restricted).toBe(false);
  });

  it('thú chỉ còn được đánh dấu đã mất khi hết lượt mở: dữ liệu mẫu đã khóa Miu (lượt #2101), hủy lượt thì mở lại (BR-KH-05)', async () => {
    const auth = await desk();
    await client.get('/api/visits', auth); // dữ liệu mẫu của visit nạp lười ở lần đầu chạm vào visit
    const mark = () => client.post('/api/pets/5/mark-deceased', { deceasedOn: '2026-10-23', confirmed: true }, auth);
    expect(ruleOf(await failure(mark()))).toBe('BR-KH-05');
    await cancel(2101);
    expect((await mark()).status).toBe(200);
  });

  it('lượt đã gọi / đã xong / đã hủy → 409 theo trạng thái (BR-TN-07)', async () => {
    for (const [visitId, from] of [[2002, 'IN_PROGRESS'], [61, 'COMPLETED']] as const) {
      const res = await failure(cancel(visitId));
      expect([res.status, res.data.message], from).toEqual([409, `Không thể chuyển trạng thái lượt tiếp nhận từ ${from} sang CANCELLED`]);
    }
    await cancel(2101);
    expect((await failure(cancel(2101))).data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ CANCELLED sang CANCELLED');
  });

  it('lý do quá 300 ký tự → 400 trước cả vai trò; không phải chuỗi → MALFORMED; chỉ lễ tân; 404; chi nhánh khác 403', async () => {
    expect((await failure(cancel(2101, { reason: 'x'.repeat(301) }, vet))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Lý do tối đa 300 ký tự' });
    expect((await cancel(2101, { reason: 'x'.repeat(300) })).status).toBe(200);
    expect((await failure(cancel(2102, { reason: ['x'] }))).data.errorCode).toBe('MALFORMED_REQUEST');
    // Jackson ép số thành chuỗi (đã dò trên BE thật): không phải lỗi.
    expect((await cancel(2102, { reason: 5 })).data.data.cancelReason).toBe('5');
    for (const who of [vet, caretaker, manager, chain, admin]) expect((await failure(cancel(2102, undefined, who))).status).toBe(403);
    expect((await failure(cancel(99999))).status).toBe(404);
    expect(direct('POST /api/visits/:visitId/cancel', desk2, { params: { visitId: '2102' } }).status).toBe(403);
  });
});

describe('gán lại lượt đã gọi (#9, UC45, BR-TN-08)', () => {
  const reassign = (visitId: number, body: object, who: () => Promise<object> = manager) => post(`/api/visits/${visitId}/reassign`, body, who);
  const OLD_STILL_ACTIVE = 'Người phụ trách vẫn đang hoạt động; chỉ gán lại lượt đã gọi khi người phụ trách không còn hoạt động (BR-TN-08)';

  it('quản lý chi nhánh gán lại lượt có người phụ trách không còn trong danh bạ: vẫn IN_PROGRESS, gỡ cờ cần gán lại, dòng của người cũ chuyển cho người mới', async () => {
    const res = await reassign(2104, { assigneeId: 6, reason: '  BS cũ đã nghỉ  ' });
    expect(res.status).toBe(200);
    expect(res.data.message).toBe('Đã gán lại lượt');
    expect(res.data.data).toMatchObject({ visitId: 2104, status: 'IN_PROGRESS', assigneeId: 6, assigneeName: 'Thu Hà', needsReassign: false });
    const order = (await client.get(`/api/orders/${res.data.data.orderId}`, await desk())).data.data;
    expect(order.lines.map((l: { ownerAccountId: number }) => l.ownerAccountId)).toEqual([6]);
    expect(visitIds(await client.get('/api/visits?needsReassign=true', await desk()))).toEqual([]);
    expect(visitIds(await client.get('/api/me/queue', await caretaker()))).toEqual([2104, 2103]);
  });

  it('người phụ trách còn đủ điều kiện → 400 BR-TN-08; bị khóa thì gán lại được (cờ cần gán lại do khóa chưa mô phỏng nên vẫn false)', async () => {
    const still = await failure(reassign(2002, { assigneeId: 5, reason: 'x' }));
    expect([still.status, still.data.message]).toEqual([400, OLD_STILL_ACTIVE]);
    const other = await makeStaff('VET');
    await client.post('/api/accounts/5/lock', { reason: 'Thử khóa' }, await admin());
    const res = await reassign(2002, { assigneeId: other, reason: 'BS #5 bị khóa' });
    expect(res.status).toBe(200);
    expect(res.data.data.assigneeId).toBe(other);
  });

  it('người mới sai chức vụ / không hoạt động → 400 BR-TN-05', async () => {
    const otherBranch = await makeStaff('CARETAKER', 2);
    const wrongRole = await failure(reassign(2104, { assigneeId: 5, reason: 'x' }));
    expect([wrongRole.status, wrongRole.data.message]).toEqual([400, ROLE_ERROR]);
    for (const assigneeId of [99999, otherBranch, 12]) {
      const res = await failure(reassign(2104, { assigneeId, reason: 'x' }));
      expect([res.status, res.data.message], String(assigneeId)).toEqual([400, NOT_ACTIVE]);
    }
  });

  it('409 đứng trước 400: lượt chưa gọi / đã xong → 409 theo trạng thái dù người mới sai chức vụ', async () => {
    const waiting = await failure(reassign(2103, { assigneeId: 5, reason: 'x' }));
    expect([waiting.status, waiting.data.message]).toEqual([409, 'Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS']);
    expect((await failure(reassign(61, { assigneeId: 6, reason: 'x' }))).data.message).toBe('Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS');
  });

  it('lý do bắt buộc (bỏ khoảng trắng hai đầu), tối đa 300; body sai → 400 trước vai trò', async () => {
    const missing = await failure(reassign(2104, { assigneeId: 6 }));
    expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Bắt buộc nhập lý do gán lại' });
    expect((await failure(reassign(2104, { assigneeId: 6, reason: '   ' }))).data.message).toBe('reason: Bắt buộc nhập lý do gán lại');
    expect((await failure(reassign(2104, { assigneeId: 6, reason: 'x'.repeat(301) }))).data.message).toBe('reason: Lý do tối đa 300 ký tự');
    expect((await failure(reassign(2104, { reason: 'x' }, vet))).data.message).toBe('assigneeId: Thiếu nhân viên được gán');
  });

  it('chỉ BRANCH_MANAGER của chi nhánh lượt: chi nhánh khác 403 phạm vi; lễ tân, VET, CARETAKER, ADMIN 403; 404', async () => {
    const otherManager = direct('POST /api/visits/:visitId/reassign', callerOf('BRANCH_MANAGER', 9, 2), { body: { assigneeId: 6, reason: 'x' }, params: { visitId: '2104' } });
    expect([otherManager.status, (otherManager.body as { errorCode: string }).errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    for (const who of [desk, vet, caretaker, admin]) expect((await failure(reassign(2104, { assigneeId: 6, reason: 'x' }, who))).status).toBe(403);
    expect((await failure(reassign(99999, { assigneeId: 6, reason: 'x' }))).status).toBe(404);
  });

  it('SUPER_MANAGER chỉ khi chi nhánh không còn BRANCH_MANAGER ACTIVE (BR-TN-08); còn quản lý → 403 phạm vi', async () => {
    const body = { assigneeId: 6, reason: 'Chi nhánh không còn quản lý' };
    const blocked = await failure(reassign(2104, body, chain));
    expect([blocked.status, blocked.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    await client.post('/api/accounts/3/lock', { reason: 'Thử khóa quản lý cuối' }, await admin());
    const res = await reassign(2104, body, chain);
    expect(res.status).toBe(200);
    expect(res.data.data.assigneeId).toBe(6);
  });
});
