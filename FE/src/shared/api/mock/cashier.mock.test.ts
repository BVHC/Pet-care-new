import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ruleIdOf } from '../api-error';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu của mock thu ngân (cashier.mock.ts), đồng hồ ghim ở thứ Ba 27/10/2026 10:00 giờ Việt Nam (chi nhánh 1 mở
// 08:00–12:00 và 13:30–19:00 thứ Hai–thứ Bảy, chủ nhật chỉ buổi sáng; cờ cấp cứu ngoài giờ bật ở chi nhánh 1):
// ca #1 RECONCILED (24/10, lễ tân #4) · ca #2 CLOSED tự chốt, chưa có số thực đếm (25/10, lễ tân #8) · ca #3 CLOSED chờ đối soát
// (26/10, lễ tân #4) · ca #4 CLOSED ở chi nhánh 2 (26/10, lễ tân #11). Phiếu thu #1 thuộc Order #5; lễ tân #4 chưa có ca OPEN.
// Order chờ thu chi nhánh 1: #2 (khách #1: khám, Amoxicillin ×10, Pate ×2) · #3 (khách #4: tiêm phòng + vaccine) · #4 (khách #2:
// Royal Canin ×1); chi nhánh 2: #8 (khách #1). Tồn chi nhánh 1: Royal Canin 12, Pate 3, Amoxicillin 200 + 500, Vitamin 0.

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

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const desk = () => as('reception@store1.vn');
const manager = () => as('manager@store1.vn');
const manager2 = () => as('manager2@store2.vn');
const vet = () => as('doctor@store1.vn');

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
const ids = (res: AxiosResponse) => res.data.data.content.map((s: { shiftId: number }) => s.shiftId);
const listShifts = async (params: Record<string, unknown> = {}, auth?: Awaited<ReturnType<typeof manager>>) =>
  client.get('/api/cashier-shifts', { params, ...(auth ?? (await manager())) });

describe('dữ liệu mẫu và đọc ca thu ngân (#17, #19, #20) — chi nhánh, chủ ca', () => {
  it('lễ tân #4 chưa có ca OPEN: { open: false, shift: null } (BR-TG-01 minh họa được)', async () => {
    const res = await client.get('/api/me/cashier-shift', await desk());
    expect(res.status).toBe(200);
    expect(res.data.data).toEqual({ open: false, shift: null });
  });

  it('danh sách của quản lý chi nhánh 1: business_date DESC, id DESC; đủ khóa JSON của hợp đồng (isAfterHours)', async () => {
    const res = await listShifts();
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 3, totalPages: 1 });
    expect(ids(res)).toEqual([3, 2, 1]);
    expect(Object.keys(res.data.data.content[0]).sort()).toEqual(
      [
        'autoClosed', 'branchId', 'businessDate', 'cashierId', 'cashierName', 'closedAt', 'countedCash', 'difference', 'expectedCash',
        'expectedTransfer', 'isAfterHours', 'openedAt', 'paymentCount', 'reconcileNote', 'reconciledAt', 'reconciledBy', 'shiftId', 'status',
      ].sort(),
    );
  });

  it('ca #3 CLOSED chờ đối soát: tổng theo phiếu thu, thực đếm lệch, difference vẫn null (chỉ đối soát mới ghi)', async () => {
    const shift = (await client.get('/api/cashier-shifts/3', await manager())).data.data;
    expect(shift).toMatchObject({
      shiftId: 3, branchId: 1, cashierId: 4, cashierName: 'Lan Chi', isAfterHours: false, businessDate: '2026-10-26', status: 'CLOSED',
      autoClosed: false, expectedCash: 576_000, expectedTransfer: 300_000, countedCash: 570_000, difference: null,
      reconcileNote: null, reconciledBy: null, reconciledAt: null, paymentCount: 3,
    });
    expect(shift.closedAt).toBeTruthy();
  });

  it('ca #2 tự chốt: lễ tân #8, autoClosed, chưa có số thực đếm; ca #1 đã đối soát: difference = thực đếm − tiền mặt hệ thống', async () => {
    expect((await client.get('/api/cashier-shifts/2', await manager())).data.data).toMatchObject({
      shiftId: 2, cashierId: 8, cashierName: 'Lễ tân mới', businessDate: '2026-10-25', status: 'CLOSED', autoClosed: true,
      expectedCash: 200_000, expectedTransfer: 80_000, countedCash: null, difference: null, paymentCount: 2,
    });
    expect((await client.get('/api/cashier-shifts/1', await manager())).data.data).toMatchObject({
      shiftId: 1, businessDate: '2026-10-24', status: 'RECONCILED', autoClosed: false, expectedCash: 108_000, expectedTransfer: 350_000,
      countedCash: 100_000, difference: -8_000, reconciledBy: 3, paymentCount: 2,
    });
  });

  it('bộ lọc status, businessDate, cashierId; phân trang 0-based; size kẹp 1–100 và page âm về 0 (không báo 400)', async () => {
    expect(ids(await listShifts({ status: 'CLOSED' }))).toEqual([3, 2]);
    expect(ids(await listShifts({ businessDate: '2026-10-25' }))).toEqual([2]);
    expect(ids(await listShifts({ cashierId: 8 }))).toEqual([2]);
    expect(ids(await listShifts({ status: 'CLOSED', cashierId: 4 }))).toEqual([3]);
    const second = await listShifts({ size: 1, page: 1 });
    expect(ids(second)).toEqual([2]);
    expect(second.data.data).toMatchObject({ page: 1, size: 1, totalElements: 3, totalPages: 3 });
    expect((await listShifts({ size: 0 })).data.data).toMatchObject({ size: 1, totalPages: 3 });
    expect((await listShifts({ size: 1000 })).data.data.size).toBe(100);
    expect((await listShifts({ page: -4 })).data.data.page).toBe(0);
    expect((await listShifts({ page: 9 })).data.data).toMatchObject({ content: [], page: 9, totalElements: 3 });
    expect((await listShifts({ status: 'OPEN' })).data.data).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
  });

  it('tham số sai kiểu → 400 MALFORMED_REQUEST (kiểm trước quyền như BE)', async () => {
    for (const params of [{ status: 'BAD' }, { businessDate: '2026-13-45' }, { businessDate: '27/10/2026' }, { cashierId: 'x' }, { size: 'x' }, { page: 1.5 }]) {
      const res = await failure(listShifts(params));
      expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    }
    expect((await failure(listShifts({ status: 'BAD' }, await desk()))).status).toBe(400);
  });

  it('danh sách chỉ BRANCH_MANAGER; quản lý chi nhánh 2 chỉ thấy ca #4', async () => {
    for (const who of [desk, vet]) {
      const res = await failure(listShifts({}, await who()));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    expect(ids(await listShifts({}, await manager2()))).toEqual([4]);
    expect((await client.get('/api/cashier-shifts/4', await manager2())).data.data).toMatchObject({
      branchId: 2, cashierId: 11, status: 'CLOSED', expectedCash: 900_000, expectedTransfer: 0, countedCash: 900_000, paymentCount: 1,
    });
  });

  it('chi tiết: quản lý đúng chi nhánh; chi nhánh khác 403 SCOPE; không có 404; lễ tân chỉ ca của mình; bác sĩ 403', async () => {
    expect((await failure(client.get('/api/cashier-shifts/4', await manager()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/cashier-shifts/1', await manager2()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    const missing = await failure(client.get('/api/cashier-shifts/999', await manager()));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy ca thu ngân #999']);
    expect((await client.get('/api/cashier-shifts/3', await desk())).data.data.cashierId).toBe(4);
    const other = await failure(client.get('/api/cashier-shifts/2', await desk()));
    expect([other.status, other.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    expect((await failure(client.get('/api/cashier-shifts/3', await vet()))).data.errorCode).toBe('ACCESS_DENIED');
    const notId = await failure(client.get('/api/cashier-shifts/abc', await manager()));
    expect([notId.status, notId.data.errorCode, notId.data.message]).toEqual([400, 'MALFORMED_REQUEST', "Tham số 'shiftId' sai kiểu dữ liệu"]);
  });
});

describe('phiếu thu mẫu (#15) và Order #5 đã thanh toán', () => {
  it('Order #5 trỏ tới phiếu #1 (hết treo), phiếu #1 gom đúng Order đó qua paymentId', async () => {
    const order = (await client.get('/api/orders/5', await desk())).data.data;
    expect(order).toMatchObject({ status: 'PAID', paymentId: 1, deliveryAllowed: true });
    const payment = (await client.get('/api/payments/1', await desk())).data.data;
    expect(payment).toMatchObject({ paymentId: 1, branchId: 1, customerId: 1, cashierShiftId: 1, method: 'CASH', amount: 108_000, receivedBy: 4, paidAt: order.paidAt });
    expect(payment.code).toMatch(/^PT-\d{4}-\d{5}$/);
    expect(payment.orders).toHaveLength(1);
    expect(payment.orders[0]).toMatchObject({ orderId: 5, status: 'PAID', totalAmount: 108_000, customerName: 'Nguyễn Thu Trang', paidAt: order.paidAt });
  });

  it('phiếu lịch sử không có Order thật trong dữ liệu mẫu (orders = []); số tiền/phương thức khớp tổng của ca', async () => {
    const two = (await client.get('/api/payments/2', await manager())).data.data;
    expect(two).toMatchObject({ cashierShiftId: 1, method: 'TRANSFER', amount: 350_000, orders: [] });
  });

  it('quyền: quản lý và lễ tân cùng chi nhánh xem được; chi nhánh khác 403 SCOPE; không có 404; bác sĩ 403', async () => {
    expect((await client.get('/api/payments/1', await manager())).status).toBe(200);
    expect((await failure(client.get('/api/payments/8', await manager()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await client.get('/api/payments/8', await manager2())).data.data.branchId).toBe(2);
    const missing = await failure(client.get('/api/payments/999', await desk()));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy phiếu thu #999']);
    expect((await failure(client.get('/api/payments/1', await vet()))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

// ─── Mở ca (#16, Ca thu ngân#1, #2; BR-TG-05, BR-CN-05) ───

const SUN = '2026-11-01';
const SAT = '2026-10-31';
const at = (date: string, hhmm: string) => `${date}T${hhmm}:00+07:00`;
const openShift = async (afterHours: boolean, auth?: Awaited<ReturnType<typeof desk>>) =>
  client.post('/api/cashier-shifts', { afterHours }, auth ?? (await desk()));
const chain = () => as('chuoi@petcare.vn');

describe('mở ca thường (#16) — chỉ trong giờ mở cửa hôm nay, không nghỉ trưa, không ngày nghỉ', () => {
  it('201 "Đã mở ca thu ngân": ca của lễ tân #4 tại chi nhánh 1, ngày mở theo giờ Việt Nam, chưa có expected*; #17 thấy ca đó', async () => {
    const res = await openShift(false);
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã mở ca thu ngân', code: 201 });
    expect(res.data.data).toEqual({
      shiftId: expect.any(Number), branchId: 1, cashierId: 4, cashierName: 'Lan Chi', isAfterHours: false, businessDate: '2026-10-27',
      status: 'OPEN', openedAt: new Date(TUE_10).toISOString(), closedAt: null, autoClosed: false, expectedCash: null, expectedTransfer: null,
      countedCash: null, difference: null, reconcileNote: null, reconciledBy: null, reconciledAt: null, paymentCount: 0,
    });
    expect(res.data.data.shiftId).toBeGreaterThan(4);
    const mine = (await client.get('/api/me/cashier-shift', await desk())).data.data;
    expect(mine).toEqual({ open: true, shift: res.data.data });
    expect(ids(await listShifts())[0]).toBe(res.data.data.shiftId);
    expect((await client.get(`/api/cashier-shifts/${res.data.data.shiftId}`, await desk())).data.data.status).toBe('OPEN');
  });

  it.each([
    ['07:59', false],
    ['08:00', true],
    ['11:59', true],
    ['12:00', false],
    ['12:45', false],
    ['13:29', false],
    ['13:30', true],
    ['18:59', true],
    ['19:00', false],
    ['23:59', false],
  ] as const)('thứ Ba %s → %s (khoảng [mở, đóng): đúng giờ đóng là đã đóng; nghỉ trưa 12:00–13:30 không mở được)', async (time, allowed) => {
    setNow(at('2026-10-27', time));
    if (allowed) {
      expect((await openShift(false)).status).toBe(201);
    } else {
      const res = await failure(openShift(false));
      expect([res.status, res.data.errorCode, res.data.message]).toEqual([400, 'BUSINESS_RULE_VIOLATION', 'Chỉ mở được ca thường trong giờ mở cửa của chi nhánh (BR-TG-05)']);
    }
  });

  it.each([
    [SAT, '15:00', true],
    [SUN, '10:00', true],
    [SUN, '11:59', true],
    [SUN, '12:00', false],
    [SUN, '13:30', false],
    [SUN, '15:00', false],
  ] as const)('thứ Bảy mở cả chiều, chủ nhật chỉ buổi sáng: %s %s → %s', async (date, time, allowed) => {
    setNow(at(date, time));
    if (allowed) expect((await openShift(false)).status).toBe(201);
    else expect(ruleOf(await failure(openShift(false)))).toBe('BR-TG-05');
  });

  it('ngày nghỉ của chi nhánh (dữ liệu mẫu) dù đang giờ làm việc → BR-TG-05; ca ngoài giờ mở được', async () => {
    const holidays = (await client.get('/api/branches/1/holidays', await manager())).data.data as { holidayDate: string }[];
    const holiday = holidays.map((h) => h.holidayDate).sort().find((d) => d > '2026-10-27')!;
    // Chốt dữ liệu chi nhánh vào localStorage trước khi dời đồng hồ, nếu không ngày nghỉ mẫu dời theo.
    await client.put('/api/branches/1/services/1', { enabled: true }, await manager());
    setNow(at(holiday, '10:00'));
    expect(ruleOf(await failure(openShift(false)))).toBe('BR-TG-05');
    const after = await openShift(true);
    expect(after.data.data).toMatchObject({ isAfterHours: true, businessDate: holiday, status: 'OPEN' });
  });
});

describe('mở ca ngoài giờ (#16, Ca thu ngân#2) — chi nhánh có cờ cấp cứu, đang ngoài giờ hoặc ngày nghỉ', () => {
  it('đang trong giờ mở cửa → 400 BR-TG-05 "hãy mở ca thường"', async () => {
    const res = await failure(openShift(true));
    expect([res.status, res.data.message]).toEqual([400, 'Đang trong giờ mở cửa: hãy mở ca thường (BR-TG-05)']);
    setNow(at('2026-10-27', '08:00'));
    expect(ruleOf(await failure(openShift(true)))).toBe('BR-TG-05');
    setNow(at('2026-10-27', '18:59'));
    expect(ruleOf(await failure(openShift(true)))).toBe('BR-TG-05');
  });

  it.each([
    ['2026-10-27', '07:59'],
    ['2026-10-27', '12:00'],
    ['2026-10-27', '13:29'],
    ['2026-10-27', '19:00'],
    ['2026-10-27', '23:30'],
    [SUN, '13:30'],
  ])('%s %s (ngoài giờ, kể cả nghỉ trưa và chiều chủ nhật) → 201, isAfterHours = true', async (date, time) => {
    setNow(at(date, time));
    const res = await openShift(true);
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ isAfterHours: true, businessDate: date, status: 'OPEN', branchId: 1 });
  });

  it('00:30 giờ Việt Nam (còn là ngày hôm trước theo UTC): ngày mở ca là ngày Việt Nam', async () => {
    setNow('2026-10-26T17:30:00Z');
    const res = await openShift(true);
    expect(res.data.data).toMatchObject({ businessDate: '2026-10-27', openedAt: '2026-10-26T17:30:00.000Z' });
  });

  it('chi nhánh không có cờ (SUPER_MANAGER tắt) → 400 BR-CN-05, kiểm trước cả "đang trong giờ"', async () => {
    await client.patch('/api/branches/1', { acceptsAfterHoursEmergency: false }, await chain());
    const inHours = await failure(openShift(true));
    expect([inHours.status, inHours.data.message]).toEqual([400, 'Chi nhánh không nhận cấp cứu ngoài giờ nên không mở được ca ngoài giờ (BR-CN-05)']);
    setNow(at('2026-10-27', '23:30'));
    expect(ruleOf(await failure(openShift(true)))).toBe('BR-CN-05');
    // Ca thường trong giờ vẫn mở được.
    setNow(TUE_10);
    expect((await openShift(false)).status).toBe(201);
  });
});

describe('mở ca — mỗi lễ tân tối đa 1 ca OPEN, dữ liệu vào, vai trò', () => {
  it('đã có ca OPEN → 409 trước mọi rule (kể cả ngoài giờ / thiếu cờ)', async () => {
    expect((await openShift(false)).status).toBe(201);
    const again = await failure(openShift(false));
    expect([again.status, again.data.errorCode, again.data.message]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái ca thu ngân (bạn đã có ca đang mở) từ OPEN sang OPEN']);
    expect((await failure(openShift(true))).status).toBe(409);
    await client.patch('/api/branches/1', { acceptsAfterHoursEmergency: false }, await chain());
    expect((await failure(openShift(true))).status).toBe(409);
    expect((await listShifts({ status: 'OPEN' })).data.data.totalElements).toBe(1);
  });

  it('afterHours thiếu / null → 400 VALIDATION_FAILED; sai kiểu hoặc không có body → 400 MALFORMED_REQUEST; false hợp lệ', async () => {
    const missing = await failure(client.post('/api/cashier-shifts', {}, await desk()));
    expect([missing.status, missing.data.errorCode, missing.data.message]).toEqual([400, 'VALIDATION_FAILED', 'afterHours: Vui lòng chọn loại ca']);
    expect((await failure(client.post('/api/cashier-shifts', { afterHours: null }, await desk()))).data.errorCode).toBe('VALIDATION_FAILED');
    for (const body of [{ afterHours: 'false' }, { afterHours: 0 }, undefined]) {
      const res = await failure(client.post('/api/cashier-shifts', body, await desk()));
      expect([res.status, res.data.errorCode]).toEqual([400, 'MALFORMED_REQUEST']);
    }
    expect((await openShift(false)).status).toBe(201);
  });

  it('chỉ lễ tân: các vai trò khác 403 ACCESS_DENIED; body sai + sai vai trò → 400 (Bean Validation chạy trước @PreAuthorize)', async () => {
    for (const who of [manager, vet, chain, () => as('caretaker@store1.vn'), () => as('admin@petcare.vn'), () => as('khach@petcare.vn')]) {
      const res = await failure(openShift(false, await who()));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED']);
    }
    const wrongRoleAndBody = await failure(client.post('/api/cashier-shifts', {}, await manager()));
    expect([wrongRoleAndBody.status, wrongRoleAndBody.data.errorCode]).toEqual([400, 'VALIDATION_FAILED']);
    expect((await listShifts({ status: 'OPEN' })).data.data.totalElements).toBe(0);
  });
});

// ─── Thu tiền: dùng chung ───

type Auth = Awaited<ReturnType<typeof desk>>;
const previewOf = async (orderIds: unknown, auth?: Auth) => client.post('/api/payments/preview', { orderIds }, auth ?? (await desk()));
const payOf = async (orderIds: unknown, method: unknown, amount: unknown, auth?: Auth) =>
  client.post('/api/payments', { orderIds, method, amount }, auth ?? (await desk()));
const stockOf = async (productId: number) => (await client.get(`/api/inventory/${productId}`, await manager())).data.data.availableQuantity as number;
const lotsOf = async (productId: number) =>
  (await client.get(`/api/inventory/${productId}/lots`, await manager())).data.data as { stockLotId: number; quantity: number; expired: boolean }[];
const adjust = async (lots: [stockLotId: number, delta: number][]) =>
  client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: lots.map(([stockLotId, quantityDelta]) => ({ stockLotId, quantityDelta })) }, await manager());
const orderOf = async (orderId: number) => (await client.get(`/api/orders/${orderId}`, await manager())).data.data;

/** Order bán lẻ đã chốt (PENDING) của khách với các dòng hàng [productId, số lượng]. */
async function retail(customerId: number, lines: [productId: number, quantity: number][]) {
  const created = (await client.post('/api/orders', { customerId }, await desk())).data.data;
  for (const [productId, quantity] of lines) {
    await client.post(`/api/orders/${created.orderId}/lines`, { lineType: 'GOODS', productId, quantity }, await desk());
  }
  return (await client.post(`/api/orders/${created.orderId}/close`, undefined, await desk())).data.data as {
    orderId: number;
    totalAmount: number;
    lines: { lineId: number }[];
  };
}

/** Lễ tân #4 mở ca thường lúc đồng hồ đang ghim (trong giờ mở cửa). */
async function withShift() {
  const auth = await desk();
  const shift = (await client.post('/api/cashier-shifts', { afterHours: false }, auth)).data.data;
  return { auth, shift };
}

describe('Order chờ thu của khách (#12) — chi nhánh của lễ tân, chốt lâu nhất trước', () => {
  const payable = async (customerId: number | string, auth?: Auth) => client.get(`/api/customers/${customerId}/payable-orders`, auth ?? (await desk()));
  const orderIds = (res: AxiosResponse) => res.data.data.map((o: { orderId: number }) => o.orderId);

  it('mảng OrderSummary của đúng khách: chỉ PENDING, chỉ chi nhánh 1 (Order #8 của chi nhánh 2 không lẫn vào)', async () => {
    const res = await payable(1);
    expect(res.status).toBe(200);
    expect(orderIds(res)).toEqual([2]);
    expect(res.data.data[0]).toMatchObject({
      orderId: 2, source: 'VISIT', status: 'PENDING', customerId: 1, customerName: 'Nguyễn Thu Trang', branchId: 1, totalAmount: 266_000, paidAt: null,
    });
    expect(res.data.data[0].code).toMatch(/^DH-/);
    expect(orderIds(await payable(4))).toEqual([3]);
    expect(orderIds(await payable(2))).toEqual([4]);
    expect(orderIds(await payable(3))).toEqual([]);
  });

  it('sắp pendingAt tăng dần rồi orderId tăng dần', async () => {
    const first = await retail(2, [[1, 1]]);
    const second = await retail(2, [[1, 1]]);
    expect(orderIds(await payable(2))).toEqual([4, first.orderId, second.orderId]);
  });

  it('khách không tồn tại → 404; chỉ lễ tân (vai trò kiểm trước cả 404); id không phải số → 400', async () => {
    const missing = await failure(payable(999));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy khách hàng #999']);
    for (const who of [manager, vet, manager2, chain]) {
      expect((await failure(payable(999, await who()))).data.errorCode).toBe('ACCESS_DENIED');
    }
    expect((await failure(payable('abc'))).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

describe('xem trước phiếu thu (#13) — không cần ca, không ghi gì; 404 → 403 chi nhánh → BR-TG-03 → BR-TG-01', () => {
  it('Order Visit có dịch vụ + thuốc + hàng: tổng 266.000, đủ tồn; lễ tân chưa mở ca vẫn xem được và không có gì đổi', async () => {
    const res = await previewOf([2]);
    expect(res.status).toBe(200);
    expect(res.data.data).toEqual({ customerId: 1, branchId: 1, totalAmount: 266_000, shortages: [] });
    expect((await orderOf(2)).status).toBe('PENDING');
    expect(await stockOf(2)).toBe(3);
    expect((await client.get('/api/me/cashier-shift', await desk())).data.data.open).toBe(false);
  });

  it('thu gộp nhiều Order của cùng khách: tổng cộng; Order chỉ có dịch vụ + vaccine không có dòng thiếu', async () => {
    const extra = await retail(1, [[1, 1]]);
    expect((await previewOf([extra.orderId, 2])).data.data).toMatchObject({ customerId: 1, totalAmount: 266_000 + 450_000, shortages: [] });
    expect((await previewOf([3])).data.data).toEqual({ customerId: 4, branchId: 1, totalAmount: 200_000, shortages: [] });
  });

  it('Order không tồn tại → 404 (id nhỏ nhất thiếu); Order chi nhánh khác → 403 SCOPE (hợp đồng ghi 400 BR-TG-03, BE trả 403)', async () => {
    const missing = await failure(previewOf([2, 999, 998]));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy đơn hàng #998']);
    for (const ids of [[8], [2, 8], [4, 8]]) {
      const res = await failure(previewOf(ids));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    }
    expect((await failure(previewOf([2, 8, 999]))).status).toBe(404);
  });

  it('khác khách → 400 BR-TG-03, kiểm trước "không PENDING" (khác với thu thật: 409 trước)', async () => {
    const res = await failure(previewOf([2, 4]));
    expect([res.status, res.data.message]).toEqual([400, 'Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh (BR-TG-03)']);
    expect(ruleOf(await failure(previewOf([1, 2])))).toBe('BR-TG-03');
  });

  it('có Order không PENDING (OPEN, PAID, CANCELLED) → 400 BR-TG-01 (thu thật trả 409)', async () => {
    for (const id of [1, 5, 6]) {
      const res = await failure(previewOf([id]));
      expect([res.status, res.data.message]).toEqual([400, 'Chỉ thu được Order đang chờ thu tiền (BR-TG-01)']);
    }
  });

  it('thiếu tồn: mỗi DÒNG của sản phẩm thiếu một mục (requested = số lượng dòng, available = tồn khả dụng), theo orderId rồi lineId', async () => {
    const vitamin = await retail(4, [[4, 1]]);
    const noStock = await previewOf([3, vitamin.orderId]);
    expect(noStock.data.data.totalAmount).toBe(200_000 + 120_000);
    expect(noStock.data.data.shortages).toEqual([
      { orderId: vitamin.orderId, lineId: vitamin.lines[0].lineId, lineType: 'GOODS', productId: 4, productName: 'Vitamin tổng hợp PetCare', requested: 1, available: 0 },
    ]);
    // Cộng dồn qua các Order: Pate 2 (Order #2) + 2 (mới) = 4 > tồn 3 → cả hai dòng đều báo.
    const pate = await retail(1, [[2, 2]]);
    const combined = (await previewOf([pate.orderId, 2])).data.data.shortages;
    expect(combined.map((x: { orderId: number; lineId: number; requested: number; available: number }) => [x.orderId, x.lineId, x.requested, x.available])).toEqual([
      [2, 23, 2, 3],
      [pate.orderId, pate.lines[0].lineId, 2, 3],
    ]);
    // Một mình Order #2 (Pate 2 ≤ 3) vẫn đủ.
    expect((await previewOf([2])).data.data.shortages).toEqual([]);
  });

  it('dòng thuốc (DRUG) thiếu tồn được báo với lineType DRUG; dòng vaccine thì không bị kiểm dù tồn bằng 0', async () => {
    await adjust([[13, -195], [14, -500]]);
    const drug = (await previewOf([2])).data.data.shortages;
    expect(drug).toEqual([{ orderId: 2, lineId: 22, lineType: 'DRUG', productId: 3, productName: 'Amoxicillin 250mg', requested: 10, available: 5 }]);
    await adjust([[15, -15]]);
    expect(await stockOf(5)).toBe(0);
    expect((await previewOf([3])).data.data.shortages).toEqual([]);
  });

  it('dữ liệu vào: orderIds trống / thiếu / > 20 / trùng / id ≤ 0 / null → 400 VALIDATION_FAILED; sai kiểu → 400 MALFORMED_REQUEST', async () => {
    const message = async (body: unknown) => (await failure(client.post('/api/payments/preview', body, await desk()))).data;
    expect(await message({})).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'orderIds: Vui lòng chọn Order cần thu' });
    expect((await message({ orderIds: [] })).message).toBe('orderIds: Vui lòng chọn Order cần thu');
    expect((await message({ orderIds: Array.from({ length: 21 }, (_, i) => i + 1) })).message).toBe('orderIds: Một phiếu thu tối đa 20 Order');
    expect((await message({ orderIds: [2, 2] })).message).toBe('distinct: Danh sách Order bị trùng');
    expect((await message({ orderIds: [0] })).message).toBe('orderIds[0]: Mã Order không hợp lệ');
    expect((await message({ orderIds: [2, -1] })).message).toBe('orderIds[1]: Mã Order không hợp lệ');
    expect((await message({ orderIds: [null] })).message).toBe('orderIds[0]: Mã Order không được trống');
    for (const orderIds of ['2', [1.5], ['2'], { 0: 2 }]) expect((await message({ orderIds })).errorCode).toBe('MALFORMED_REQUEST');
    // Đúng 20 id vẫn qua kiểm dữ liệu rồi mới 404 vì không có Order.
    expect((await failure(previewOf(Array.from({ length: 20 }, (_, i) => 500 + i)))).status).toBe(404);
  });

  it('chỉ lễ tân: vai trò khác 403; body sai + sai vai trò → 400 (Bean Validation trước @PreAuthorize)', async () => {
    for (const who of [manager, vet, manager2, chain, () => as('khach@petcare.vn')]) {
      expect((await failure(previewOf([2], await who()))).data.errorCode).toBe('ACCESS_DENIED');
    }
    expect((await failure(client.post('/api/payments/preview', {}, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });
});

describe('thu tiền (#14, Order#8, BR-TG-01…04) — cần ca OPEN của chính mình', () => {
  it('chưa mở ca → 400 BR-TG-01, kiểm đầu tiên (trước 404 / 403 / 409 / số tiền); không đổi gì', async () => {
    for (const ids of [[4], [999], [8], [5]]) {
      const res = await failure(payOf(ids, 'CASH', 1));
      expect([res.status, res.data.message]).toEqual([400, 'Bạn chưa mở ca thu ngân (BR-TG-01)']);
    }
    expect((await orderOf(4)).status).toBe('PENDING');
    expect(await stockOf(1)).toBe(12);
  });

  it('một Order, tiền mặt: 201 "Đã thu tiền", phiếu gắn ca của người thu, Order → PAID (paidAt, paymentId), trừ kho', async () => {
    const { auth, shift } = await withShift();
    const res = await payOf([4], 'CASH', 450_000, auth);
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã thu tiền', code: 201 });
    const payment = res.data.data;
    expect(payment).toMatchObject({
      branchId: 1, customerId: 2, cashierShiftId: shift.shiftId, method: 'CASH', amount: 450_000, receivedBy: 4, paidAt: new Date(TUE_10).toISOString(),
    });
    expect(payment.paymentId).toBeGreaterThan(8);
    expect(payment.orders).toHaveLength(1);
    expect(payment.orders[0]).toMatchObject({ orderId: 4, status: 'PAID', totalAmount: 450_000, customerName: 'Lê Hoàng Long', paidAt: payment.paidAt });
    // Order đã PAID: giao hàng được, trỏ về phiếu; hết khỏi danh sách chờ thu.
    expect(await orderOf(4)).toMatchObject({ status: 'PAID', paymentId: payment.paymentId, paidAt: payment.paidAt, deliveryAllowed: true });
    expect((await client.get('/api/customers/2/payable-orders', auth)).data.data).toEqual([]);
    expect((await client.get(`/api/payments/${payment.paymentId}`, auth)).data.data).toEqual(payment);
    expect(await stockOf(1)).toBe(11);
    // Phiếu nằm trong ca: đếm ngay, tổng kỳ vọng chỉ có sau khi chốt.
    expect((await client.get('/api/me/cashier-shift', auth)).data.data.shift).toMatchObject({ paymentCount: 1, expectedCash: null, expectedTransfer: null });
  });

  it('mã PT-yyMM-nnnnn liên tục theo tháng (nối tiếp số của dữ liệu mẫu), id phiếu tăng dần', async () => {
    const { auth } = await withShift();
    const last = (await client.get('/api/payments/8', await manager2())).data.data.code as string;
    const next = (n: number) => `${last.slice(0, 8)}${String(Number(last.slice(8)) + n).padStart(5, '0')}`;
    expect(last).toMatch(/^PT-2610-\d{5}$/);
    const first = (await payOf([4], 'CASH', 450_000, auth)).data.data;
    const extra = await retail(2, [[1, 1]]);
    const second = (await payOf([extra.orderId], 'TRANSFER', 450_000, auth)).data.data;
    expect([first.code, second.code]).toEqual([next(1), next(2)]);
    expect(second.paymentId).toBe(first.paymentId + 1);
  });

  it('thu gộp 2 Order của cùng khách bằng chuyển khoản: một phiếu, cả hai PAID cùng paymentId, kho trừ theo FEFO', async () => {
    const { auth } = await withShift();
    const extra = await retail(1, [[1, 1]]);
    const res = await payOf([extra.orderId, 2], 'TRANSFER', 266_000 + 450_000, auth);
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ customerId: 1, method: 'TRANSFER', amount: 716_000 });
    expect(res.data.data.orders.map((o: { orderId: number; status: string }) => [o.orderId, o.status])).toEqual([[2, 'PAID'], [extra.orderId, 'PAID']]);
    expect((await orderOf(2)).paymentId).toBe(res.data.data.paymentId);
    expect((await orderOf(extra.orderId)).paymentId).toBe(res.data.data.paymentId);
    expect(await stockOf(2)).toBe(1);
    expect(await stockOf(1)).toBe(11);
    // Amoxicillin ×10 lấy từ lô hết hạn sớm nhất (AMX2405 = lô 13), lô sau (14) giữ nguyên.
    expect((await lotsOf(3)).map((l) => [l.stockLotId, l.quantity])).toEqual([[13, 190], [14, 500]]);
  });

  it('FEFO chia qua nhiều lô: lô sớm hết rồi mới sang lô sau', async () => {
    const { auth } = await withShift();
    await adjust([[13, -195]]);
    expect((await payOf([2], 'CASH', 266_000, auth)).status).toBe(201);
    expect((await lotsOf(3)).map((l) => [l.stockLotId, l.quantity])).toEqual([[13, 0], [14, 495]]);
    expect(await stockOf(3)).toBe(495);
  });

  it('không bao giờ xuất lô đã hết hạn dù hạn sớm nhất; tồn khả dụng không tính lô đó', async () => {
    // Chốt tồn kho vào localStorage lúc 27/10 (lô AMX2405 hạn 16/11) rồi dời đồng hồ sang 17/11: lô 13 hết hạn.
    await client.put('/api/inventory/1/min-quantity', { minQuantity: 5 }, await manager());
    setNow(at('2026-11-17', '10:00'));
    const { auth } = await withShift();
    expect((await lotsOf(3)).find((l) => l.stockLotId === 13)).toMatchObject({ quantity: 200, expired: true });
    expect(await stockOf(3)).toBe(500);
    expect((await payOf([2], 'CASH', 266_000, auth)).status).toBe(201);
    expect((await lotsOf(3)).map((l) => [l.stockLotId, l.quantity])).toEqual([[13, 200], [14, 490]]);
    expect(await stockOf(3)).toBe(490);
  });

  it('vaccine không bị kiểm và không bị trừ kho lúc thu (đã trừ khi tiêm), dù tồn khả dụng bằng 0', async () => {
    const { auth } = await withShift();
    await adjust([[15, -15]]);
    const res = await payOf([3], 'CASH', 200_000, auth);
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ customerId: 4, amount: 200_000 });
    expect((await lotsOf(5)).map((l) => [l.stockLotId, l.quantity])).toEqual([[16, 2], [15, 0]]);
  });

  it('số tiền khác tổng → 400 BR-TG-02 với câu nêu cả hai số, kể cả 0; không đổi gì; kiểm trước BR-BH-04', async () => {
    const { auth } = await withShift();
    for (const amount of [449_999, 450_001, 0]) {
      const res = await failure(payOf([4], 'CASH', amount, auth));
      expect([res.status, res.data.message]).toEqual([400, `Số tiền thu (${amount}) khác tổng phải trả (450000) (BR-TG-02)`]);
    }
    expect(await orderOf(4)).toMatchObject({ status: 'PENDING', paymentId: null });
    expect(await stockOf(1)).toBe(12);
    const vitamin = await retail(4, [[4, 1]]);
    expect(ruleOf(await failure(payOf([vitamin.orderId], 'CASH', 1, auth)))).toBe('BR-TG-02');
  });

  it('khác khách → 400 BR-TG-03 (kiểm sau 409, trước số tiền)', async () => {
    const { auth } = await withShift();
    const res = await failure(payOf([3, 4], 'CASH', 1, auth));
    expect([res.status, res.data.message]).toEqual([400, 'Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh (BR-TG-03)']);
  });

  it('Order chi nhánh khác → 403 SCOPE (hợp đồng ghi 400 BR-TG-03, BE trả 403), kiểm trước 409 và BR-TG-03', async () => {
    const { auth } = await withShift();
    for (const ids of [[8], [2, 8], [4, 8], [5, 8]]) {
      const res = await failure(payOf(ids, 'CASH', 1, auth));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    }
  });

  it('Order không PENDING → 409 INVALID_STATE_TRANSITION (khác preview: 400 BR-TG-01), trước BR-TG-03; thu hai lần cùng Order → 409', async () => {
    const { auth } = await withShift();
    const cases: [number[], string][] = [
      [[5], 'PAID'],
      [[1], 'OPEN'],
      [[6], 'CANCELLED'],
      [[1, 4], 'OPEN'],
      [[4, 5], 'PAID'],
    ];
    for (const [ids, status] of cases) {
      const res = await failure(payOf(ids, 'CASH', 1, auth));
      expect([res.status, res.data.errorCode, res.data.message]).toEqual([
        409,
        'INVALID_STATE_TRANSITION',
        `Không thể chuyển trạng thái đơn hàng (chỉ thu được Order đang chờ thu tiền) từ ${status} sang PAID`,
      ]);
    }
    expect((await payOf([4], 'CASH', 450_000, auth)).status).toBe(201);
    expect((await failure(payOf([4], 'CASH', 450_000, auth))).status).toBe(409);
    expect(await stockOf(1)).toBe(11);
  });

  it('Order không tồn tại → 404 (id nhỏ nhất thiếu), kiểm trước chi nhánh', async () => {
    const { auth } = await withShift();
    const res = await failure(payOf([999, 998, 8], 'CASH', 1, auth));
    expect([res.status, res.data.message]).toEqual([404, 'Không tìm thấy đơn hàng #998']);
  });

  it('thiếu tồn → 400 BR-BH-04 nêu tên sản phẩm; không trừ gì, không tạo phiếu, Order vẫn PENDING', async () => {
    const { auth } = await withShift();
    const vitamin = await retail(4, [[4, 1]]);
    const res = await failure(payOf([vitamin.orderId], 'CASH', 120_000, auth));
    expect([res.status, res.data.message]).toEqual([
      400,
      'Không đủ tồn kho cho: Vitamin tổng hợp PetCare (cần 1, còn 0). Xem trước phiếu thu để biết dòng cần xử lý (BR-BH-04)',
    ]);
    expect((await orderOf(vitamin.orderId)).status).toBe('PENDING');
    expect((await client.get('/api/me/cashier-shift', auth)).data.data.shift.paymentCount).toBe(0);
  });

  it('thiếu tồn tính cộng dồn theo SẢN PHẨM qua các Order (câu báo theo sản phẩm, preview theo dòng); sản phẩm đủ tồn cũng không bị trừ', async () => {
    const { auth } = await withShift();
    const pate = await retail(1, [[2, 2]]);
    const res = await failure(payOf([2, pate.orderId], 'CASH', 266_000 + pate.totalAmount, auth));
    expect(res.data.message).toBe('Không đủ tồn kho cho: Pate Whiskas 85g (cần 4, còn 3). Xem trước phiếu thu để biết dòng cần xử lý (BR-BH-04)');
    expect(await stockOf(2)).toBe(3);
    expect(await stockOf(3)).toBe(700);
    expect((await orderOf(2)).status).toBe('PENDING');
  });

  it('nhiều sản phẩm thiếu: liệt kê theo mã sản phẩm tăng dần, ngăn bằng dấu phẩy', async () => {
    const { auth } = await withShift();
    await adjust([[13, -195], [14, -500]]);
    const pate = await retail(1, [[2, 2]]);
    const res = await failure(payOf([2, pate.orderId], 'CASH', 266_000 + pate.totalAmount, auth));
    expect(res.data.message).toBe(
      'Không đủ tồn kho cho: Pate Whiskas 85g (cần 4, còn 3), Amoxicillin 250mg (cần 10, còn 5). Xem trước phiếu thu để biết dòng cần xử lý (BR-BH-04)',
    );
  });

  it('dữ liệu vào: method / amount / orderIds sai → 400 theo trường (gộp nhiều lỗi); sai kiểu → MALFORMED_REQUEST; amount 0 hợp lệ về kiểu', async () => {
    const { auth } = await withShift();
    const post = async (body: unknown) => (await failure(client.post('/api/payments', body, auth))).data;
    const empty = await post({});
    expect(empty.errorCode).toBe('VALIDATION_FAILED');
    for (const part of ['orderIds: Vui lòng chọn Order cần thu', 'method: Vui lòng chọn phương thức thanh toán', 'amount: Vui lòng nhập số tiền thu']) {
      expect(empty.message).toContain(part);
    }
    expect((await post({ orderIds: [4], method: 'CASH' })).message).toBe('amount: Vui lòng nhập số tiền thu');
    expect((await post({ orderIds: [4], amount: 1 })).message).toBe('method: Vui lòng chọn phương thức thanh toán');
    expect((await post({ orderIds: [4], method: 'CASH', amount: -1 })).message).toBe('amount: Số tiền thu không được âm');
    expect((await post({ orderIds: [4, 4], method: 'CASH', amount: 1 })).message).toBe('distinct: Danh sách Order bị trùng');
    expect((await post({ orderIds: Array.from({ length: 21 }, (_, i) => i + 1), method: 'CASH', amount: 1 })).message).toBe('orderIds: Một phiếu thu tối đa 20 Order');
    for (const body of [
      { orderIds: [4], method: 'CHECK', amount: 1 },
      { orderIds: [4], method: 'cash', amount: 1 },
      { orderIds: [4], method: 'CASH', amount: 1.5 },
      { orderIds: [4], method: 'CASH', amount: '450000' },
      { orderIds: '4', method: 'CASH', amount: 1 },
    ]) {
      expect((await post(body)).errorCode).toBe('MALFORMED_REQUEST');
    }
    // Đúng 20 Order qua kiểm dữ liệu rồi mới 404.
    expect((await failure(payOf(Array.from({ length: 20 }, (_, i) => 500 + i), 'CASH', 0, auth))).status).toBe(404);
  });

  it('chỉ lễ tân: vai trò khác 403 (kiểm trước cả BR-TG-01); body sai + sai vai trò → 400', async () => {
    for (const who of [manager, vet, manager2, chain, () => as('khach@petcare.vn')]) {
      expect((await failure(payOf([4], 'CASH', 450_000, await who()))).data.errorCode).toBe('ACCESS_DENIED');
    }
    expect((await failure(client.post('/api/payments', {}, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });
});

describe('chốt ca (#18, Ca thu ngân#3) — chủ ca nhập tiền mặt thực đếm; tổng kỳ vọng theo phương thức', () => {
  const close = async (shiftId: number | string, body: unknown, auth?: Auth) =>
    client.post(`/api/cashier-shifts/${shiftId}/close`, body, auth ?? (await desk()));

  it('200 "Đã chốt ca thu ngân": expectedCash / expectedTransfer = tổng phiếu thu của ca theo phương thức; difference chưa có (chờ đối soát)', async () => {
    const { auth, shift } = await withShift();
    await payOf([4], 'CASH', 450_000, auth);
    await payOf([3], 'TRANSFER', 200_000, auth);
    setNow(at('2026-10-27', '18:00'));
    const res = await close(shift.shiftId, { countedCash: 440_000 }, auth);
    expect(res.status).toBe(200);
    expect(res.data).toMatchObject({ message: 'Đã chốt ca thu ngân', code: 200 });
    expect(res.data.data).toMatchObject({
      shiftId: shift.shiftId, status: 'CLOSED', autoClosed: false, closedAt: new Date(at('2026-10-27', '18:00')).toISOString(),
      expectedCash: 450_000, expectedTransfer: 200_000, countedCash: 440_000, difference: null, reconcileNote: null, reconciledBy: null, paymentCount: 2,
    });
    expect((await client.get('/api/me/cashier-shift', auth)).data.data).toEqual({ open: false, shift: null });
    expect((await client.get(`/api/cashier-shifts/${shift.shiftId}`, await manager())).data.data).toEqual(res.data.data);
  });

  it('ca chưa có phiếu thu: tổng 0 / 0; 0 là số thực đếm hợp lệ; sau khi chốt không thu được nữa (BR-TG-01) nhưng mở ca mới thu lại được', async () => {
    const { auth, shift } = await withShift();
    const res = await close(shift.shiftId, { countedCash: 0 }, auth);
    expect(res.data.data).toMatchObject({ status: 'CLOSED', expectedCash: 0, expectedTransfer: 0, countedCash: 0, paymentCount: 0 });
    expect(ruleOf(await failure(payOf([4], 'CASH', 450_000, auth)))).toBe('BR-TG-01');
    expect((await client.post('/api/cashier-shifts', { afterHours: false }, auth)).status).toBe(201);
    expect((await payOf([4], 'CASH', 450_000, auth)).status).toBe(201);
  });

  it('chốt hai lần → 409 INVALID_STATE_TRANSITION; ca đã đối soát cũng 409', async () => {
    const { auth, shift } = await withShift();
    await close(shift.shiftId, { countedCash: 0 }, auth);
    const again = await failure(close(shift.shiftId, { countedCash: 0 }, auth));
    expect([again.status, again.data.errorCode, again.data.message]).toEqual([409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái ca thu ngân từ CLOSED sang CLOSED']);
    expect((await failure(close(3, { countedCash: 0 }))).data.message).toBe('Không thể chuyển trạng thái ca thu ngân từ CLOSED sang CLOSED');
    expect((await failure(close(1, { countedCash: 0 }))).data.message).toBe('Không thể chuyển trạng thái ca thu ngân từ RECONCILED sang CLOSED');
  });

  it('chỉ chủ ca: ca của lễ tân khác (kể cả đã chốt) và ca chi nhánh khác → 403 SCOPE, kiểm trước 409; không có → 404', async () => {
    for (const id of [2, 4]) {
      const res = await failure(close(id, { countedCash: 0 }));
      expect([res.status, res.data.errorCode]).toEqual([403, 'ACCESS_DENIED_SCOPE_MISMATCH']);
    }
    const missing = await failure(close(999, { countedCash: 0 }));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy ca thu ngân #999']);
  });

  it('countedCash thiếu / âm → 400 VALIDATION_FAILED; sai kiểu hoặc shiftId không phải số → 400 MALFORMED_REQUEST', async () => {
    const { auth, shift } = await withShift();
    expect((await failure(close(shift.shiftId, {}, auth))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'countedCash: Vui lòng nhập số tiền mặt thực đếm' });
    expect((await failure(close(shift.shiftId, { countedCash: -1 }, auth))).data.message).toBe('countedCash: Số tiền thực đếm không được âm');
    for (const countedCash of [1.5, '100', true]) {
      expect((await failure(close(shift.shiftId, { countedCash }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    const notId = await failure(close('abc', { countedCash: 0 }, auth));
    expect([notId.status, notId.data.message]).toEqual([400, "Tham số 'shiftId' sai kiểu dữ liệu"]);
  });

  it('chỉ lễ tân: vai trò khác 403 ACCESS_DENIED; body sai + sai vai trò → 400', async () => {
    for (const who of [manager, vet, manager2, chain]) {
      expect((await failure(close(3, { countedCash: 0 }, await who()))).data.errorCode).toBe('ACCESS_DENIED');
    }
    expect((await failure(close(3, {}, await manager()))).data.errorCode).toBe('VALIDATION_FAILED');
  });
});

describe('đối soát ca (#21, Ca thu ngân#6) — BRANCH_MANAGER chi nhánh, ca CLOSED, ghi chú bắt buộc', () => {
  const reconcile = async (shiftId: number | string, body: unknown, auth?: Auth) =>
    client.post(`/api/cashier-shifts/${shiftId}/reconcile`, body, auth ?? (await manager()));

  it('ca đã chốt có thực đếm: 200 "Đã đối soát ca thu ngân", difference = thực đếm − tiền mặt hệ thống, ghi chú bỏ khoảng trắng hai đầu', async () => {
    const res = await reconcile(3, { note: '  Thiếu 6.000đ, đã hỏi lễ tân  ' });
    expect(res.status).toBe(200);
    expect(res.data).toMatchObject({ message: 'Đã đối soát ca thu ngân', code: 200 });
    expect(res.data.data).toMatchObject({
      shiftId: 3, status: 'RECONCILED', expectedCash: 576_000, expectedTransfer: 300_000, countedCash: 570_000, difference: -6_000,
      reconcileNote: 'Thiếu 6.000đ, đã hỏi lễ tân', reconciledBy: 3, reconciledAt: new Date(TUE_10).toISOString(), autoClosed: false, paymentCount: 3,
    });
    expect((await client.get('/api/cashier-shifts/3', await desk())).data.data).toEqual(res.data.data);
    expect(ids(await listShifts({ status: 'RECONCILED' }))).toEqual([3, 1]);
  });

  it('countedCash gửi lên ghi đè số lễ tân đã nhập; 0 là số được gửi (không phải thiếu)', async () => {
    expect((await reconcile(3, { countedCash: 576_000, note: 'Đếm lại đủ' })).data.data).toMatchObject({ countedCash: 576_000, difference: 0 });
    localStorage.clear();
    expect((await reconcile(3, { countedCash: 0, note: 'Mất toàn bộ' })).data.data).toMatchObject({ countedCash: 0, difference: -576_000 });
  });

  it('ca tự chốt chưa có thực đếm: không gửi countedCash → 400 BR-TG-05; gửi (kể cả 0) thì đối soát được', async () => {
    const res = await failure(reconcile(2, { note: 'Lễ tân quên chốt' }));
    expect([res.status, res.data.message]).toEqual([400, 'Ca tự chốt chưa có số tiền thực đếm: vui lòng nhập số tiền khi đối soát (BR-TG-05)']);
    expect((await listShifts({ status: 'CLOSED' })).data.data.totalElements).toBe(2);
    const ok = (await reconcile(2, { countedCash: 195_000, note: 'Quản lý đếm lại' })).data.data;
    expect(ok).toMatchObject({ status: 'RECONCILED', autoClosed: true, expectedCash: 200_000, countedCash: 195_000, difference: -5_000 });
    localStorage.clear();
    expect((await reconcile(2, { countedCash: 0, note: 'Không còn tiền' })).data.data).toMatchObject({ countedCash: 0, difference: -200_000 });
  });

  it('không phải CLOSED → 409 (trước BR-TG-05): ca đã đối soát, ca đang mở; đối soát hai lần → 409', async () => {
    const already = await failure(reconcile(1, { note: 'x' }));
    expect([already.status, already.data.errorCode, already.data.message]).toEqual([
      409,
      'INVALID_STATE_TRANSITION',
      'Không thể chuyển trạng thái ca thu ngân (chỉ đối soát được ca đã chốt) từ RECONCILED sang RECONCILED',
    ]);
    const { shift } = await withShift();
    const open = await failure(reconcile(shift.shiftId, { note: 'x' }));
    expect([open.status, open.data.message]).toEqual([409, 'Không thể chuyển trạng thái ca thu ngân (chỉ đối soát được ca đã chốt) từ OPEN sang RECONCILED']);
    expect((await reconcile(3, { note: 'x' })).status).toBe(200);
    expect((await failure(reconcile(3, { note: 'x' }))).status).toBe(409);
  });

  it('chi nhánh khác → 403 SCOPE (cả hai chiều); không có → 404; lễ tân và vai trò khác → 403 ACCESS_DENIED', async () => {
    expect((await failure(reconcile(3, { note: 'x' }, await manager2()))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(reconcile(4, { note: 'x' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await reconcile(4, { note: 'Chi nhánh 2 tự đối soát' }, await manager2())).data.data).toMatchObject({ shiftId: 4, difference: 0, status: 'RECONCILED' });
    const missing = await failure(reconcile(999, { note: 'x' }));
    expect([missing.status, missing.data.message]).toEqual([404, 'Không tìm thấy ca thu ngân #999']);
    for (const who of [desk, vet, chain]) {
      expect((await failure(reconcile(3, { note: 'x' }, await who()))).data.errorCode).toBe('ACCESS_DENIED');
    }
  });

  it('dữ liệu vào: note bắt buộc không rỗng ≤ 1000; countedCash ≥ 0; kiểm trước vai trò và 404', async () => {
    for (const body of [{}, { note: '' }, { note: '   ' }, { note: null }]) {
      expect((await failure(reconcile(3, body))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'note: Vui lòng nhập ghi chú đối soát' });
    }
    expect((await failure(reconcile(3, { note: 'x'.repeat(1001) }))).data.message).toBe('note: Ghi chú tối đa 1000 ký tự');
    expect((await failure(reconcile(3, { countedCash: -1, note: 'x' }))).data.message).toBe('countedCash: Số tiền thực đếm không được âm');
    const both = (await failure(reconcile(3, { countedCash: -1, note: ' ' }))).data.message;
    expect(both).toContain('countedCash: Số tiền thực đếm không được âm');
    expect(both).toContain('note: Vui lòng nhập ghi chú đối soát');
    for (const body of [{ countedCash: 1.5, note: 'x' }, { countedCash: '5', note: 'x' }, { note: 5 }]) {
      expect((await failure(reconcile(3, body))).data.errorCode).toBe('MALFORMED_REQUEST');
    }
    expect((await failure(reconcile(999, {}))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(reconcile(3, {}, await desk()))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(reconcile('abc', { note: 'x' }))).data.message).toBe("Tham số 'shiftId' sai kiểu dữ liệu");
    expect((await reconcile(3, { note: 'x'.repeat(1000) })).status).toBe(200);
  });
});

describe('bao trùm: bảng route, đăng nhập, vai trò, lưu trữ, tên lễ tân', () => {
  it('đăng ký đủ 10 route của sales-v1 #12–21 (khóa METHOD /api/path)', async () => {
    const { cashierRoutes } = await import('./cashier.mock');
    expect(Object.keys(cashierRoutes).sort()).toEqual(
      [
        'GET /api/cashier-shifts',
        'GET /api/cashier-shifts/:shiftId',
        'GET /api/customers/:customerId/payable-orders',
        'GET /api/me/cashier-shift',
        'GET /api/payments/:paymentId',
        'POST /api/cashier-shifts',
        'POST /api/cashier-shifts/:shiftId/close',
        'POST /api/cashier-shifts/:shiftId/reconcile',
        'POST /api/payments',
        'POST /api/payments/preview',
      ].sort(),
    );
  });

  it('chưa đăng nhập → 401; tài khoản còn mustChangePassword (lễ tân #8) → 400 BR-TK-17 ở mọi route', async () => {
    const calls: [string, string, unknown][] = [
      ['get', '/api/me/cashier-shift', undefined],
      ['get', '/api/cashier-shifts', undefined],
      ['get', '/api/cashier-shifts/3', undefined],
      ['get', '/api/payments/1', undefined],
      ['get', '/api/customers/1/payable-orders', undefined],
      ['post', '/api/payments/preview', { orderIds: [4] }],
      ['post', '/api/payments', { orderIds: [4], method: 'CASH', amount: 450_000 }],
      ['post', '/api/cashier-shifts', { afterHours: false }],
      ['post', '/api/cashier-shifts/3/close', { countedCash: 0 }],
      ['post', '/api/cashier-shifts/3/reconcile', { note: 'x' }],
    ];
    const newStaff = await as('newstaff@store1.vn');
    for (const [method, url, body] of calls) {
      const call = (auth?: object) => (method === 'get' ? client.get(url, auth) : client.post(url, body, auth));
      expect((await failure(call())).status).toBe(401);
      expect(ruleOf(await failure(call(newStaff)))).toBe('BR-TK-17');
    }
  });

  it.each([
    ['admin@petcare.vn'],
    ['chuoi@petcare.vn'],
    ['doctor@store1.vn'],
    ['caretaker@store1.vn'],
    ['khach@petcare.vn'],
  ])('%s (SUPER_MANAGER, ADMIN, VET, CARETAKER, CUSTOMER) bị 403 ACCESS_DENIED ở cả 10 route', async (email) => {
    const auth = await as(email);
    const denied = async (request: Promise<unknown>) => (await failure(request)).data.errorCode;
    expect(await denied(client.get('/api/me/cashier-shift', auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.get('/api/cashier-shifts', auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.get('/api/cashier-shifts/3', auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.get('/api/payments/1', auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.get('/api/customers/1/payable-orders', auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.post('/api/payments/preview', { orderIds: [4] }, auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.post('/api/payments', { orderIds: [4], method: 'CASH', amount: 450_000 }, auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.post('/api/cashier-shifts', { afterHours: false }, auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.post('/api/cashier-shifts/3/close', { countedCash: 0 }, auth))).toBe('ACCESS_DENIED');
    expect(await denied(client.post('/api/cashier-shifts/3/reconcile', { note: 'x' }, auth))).toBe('ACCESS_DENIED');
  });

  it('trạng thái lưu ở localStorage petcare-mock-cashier (qua F5 vẫn còn); dữ liệu hỏng → dùng lại dữ liệu mẫu', async () => {
    await withShift();
    expect(JSON.parse(localStorage.getItem('petcare-mock-cashier')!).shifts).toHaveLength(5);
    const reloaded = axios.create();
    installMockApi(reloaded);
    const login = await reloaded.post('/api/auth/login', { email: 'reception@store1.vn', password: MOCK_PASSWORD });
    const mine = await reloaded.get('/api/me/cashier-shift', { headers: { Authorization: `Bearer ${login.data.data.accessToken}` } });
    expect(mine.data.data.open).toBe(true);
    localStorage.setItem('petcare-mock-cashier', '{không phải JSON');
    expect(ids(await listShifts())).toEqual([3, 2, 1]);
  });

  it('cashierName lấy từ danh bạ nhân viên, kể cả nhân viên tạo lúc chạy (UC08, extraStaff); chưa biết tên thì "Lễ tân #id"', async () => {
    const created = await client.post(
      '/api/staff',
      { email: 'moi@store1.vn', phone: '0900000999', fullName: 'Ngô Bảo Châu', role: 'RECEPTIONIST' },
      await manager(),
    );
    const accountId = created.data.data.accountId as number;
    const { staffNameOf } = await import('./identity-admin.mock');
    expect([staffNameOf(4), staffNameOf(accountId), staffNameOf(98765)]).toEqual(['Lan Chi', 'Ngô Bảo Châu', null]);
    const state = {
      shiftSeq: 100, paymentSeq: 100, counters: {}, payments: [],
      shifts: [accountId, 98765].map((cashierId, i) => ({
        shiftId: 50 + i, branchId: 1, cashierId, isAfterHours: false, businessDate: '2026-10-26', status: 'OPEN', openedAt: at('2026-10-26', '08:00'),
        closedAt: null, autoClosed: false, countedCash: null, difference: null, reconcileNote: null, reconciledBy: null, reconciledAt: null,
      })),
    };
    localStorage.setItem('petcare-mock-cashier', JSON.stringify(state));
    expect((await client.get('/api/cashier-shifts/50', await manager())).data.data.cashierName).toBe('Ngô Bảo Châu');
    expect((await client.get('/api/cashier-shifts/51', await manager())).data.data.cashierName).toBe('Lễ tân #98765');
  });

  it('ca ngoài giờ vắt qua nửa đêm vẫn thu được và thuộc ngày mở ca; mã phiếu theo THÁNG Việt Nam (00:30 ngày 1/11 → PT-2611-00001)', async () => {
    setNow(at('2026-10-31', '23:30'));
    const auth = await desk();
    const shift = (await client.post('/api/cashier-shifts', { afterHours: true }, auth)).data.data;
    expect(shift).toMatchObject({ isAfterHours: true, businessDate: '2026-10-31' });
    setNow(at('2026-11-01', '00:30'));
    const payment = (await payOf([4], 'CASH', 450_000, auth)).data.data;
    expect(payment).toMatchObject({ cashierShiftId: shift.shiftId, code: 'PT-2611-00001' });
    expect((await client.get('/api/me/cashier-shift', auth)).data.data.shift).toMatchObject({ businessDate: '2026-10-31', paymentCount: 1 });
  });
});
