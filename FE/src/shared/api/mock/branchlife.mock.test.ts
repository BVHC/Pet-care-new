import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BranchLifecycle, ClosureImpact } from '../../types/branchlife';
import { addDays, localIsoDate } from '../../utils/branch-schedule';
import { bookedOfBranch } from './branch.mock';
import { registerClosureSources } from './branchlife.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';
import { pendingRefundCount } from './returns.mock';
import { openOnlineOrderCount } from './shop.mock';
import { inTransitTransfersOf } from './stockops.mock';

// Dữ liệu mẫu: chi nhánh 1 ACTIVE (manager@store1.vn; 3 lịch hẹn mẫu + đặt chỗ lưu trú BOOKED của mock lưu trú, kho có tồn, nhiều nhân viên),
// 2 DRAFT có quản lý (manager2@store2.vn) nhưng chưa có giờ mở cửa, 3 DRAFT không có quản lý / nhân viên / tồn kho (dùng để thử đóng cửa thành công
// bằng cách ép trạng thái trong localStorage, vì chi nhánh có nhân viên không đóng được: BR-QT-04 không cho gỡ quản lý cuối).
let client: AxiosInstance;
let restoreSources: (() => void) | undefined;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});
afterEach(() => {
  restoreSources?.();
  restoreSources = undefined;
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const chain = () => as('chuoi@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const impact = async (id: number, auth?: object) => (await client.get(`/api/branches/${id}/closure-impact`, auth ?? (await chain()))).data.data as ClosureImpact;
const suspend = (id: number, body: object, auth: object) => client.post(`/api/branches/${id}/suspend`, body, auth);
const close = (id: number, body: object, auth: object) => client.post(`/api/branches/${id}/close`, body, auth);
const resume = (id: number, auth: object) => client.post(`/api/branches/${id}/resume`, undefined, auth);
const life = (res: AxiosResponse) => res.data.data as BranchLifecycle;

/** Ép trạng thái của một chi nhánh trong localStorage (state chỉ chứa `branches`: các bảng còn lại được `load()` gộp từ dữ liệu mẫu). */
async function force(statuses: Record<number, string>) {
  const all = (await client.get('/api/branches', await chain())).data.data as { branchId: number; status: string }[];
  const stored = JSON.parse(localStorage.getItem('petcare-mock-branch') ?? '{}') as object;
  localStorage.setItem('petcare-mock-branch', JSON.stringify({ ...stored, branches: all.map((b) => ({ ...b, status: statuses[b.branchId] ?? b.status })) }));
}

const ROLES_DENIED = ['admin@petcare.vn', 'manager@store1.vn', 'reception@store1.vn', 'doctor@store1.vn', 'caretaker@store1.vn', 'khach@petcare.vn'];
const CALLS: [string, (auth: object) => Promise<unknown>][] = [
  ['closure-impact (#1)', (auth) => client.get('/api/branches/1/closure-impact', auth)],
  ['suspend (#2)', (auth) => suspend(1, { reason: 'Sửa chữa', cancelBookings: true }, auth)],
  ['resume (#3)', (auth) => resume(1, auth)],
  ['close (#4)', (auth) => close(1, { reason: 'Hết hợp đồng thuê', cancelBookings: true }, auth)],
];

describe('quyền: chỉ SUPER_MANAGER', () => {
  describe.each(CALLS)('%s', (_name, call) => {
    it.each(ROLES_DENIED)('%s → 403 ACCESS_DENIED', async (email) => {
      const res = await failure(call(await as(email)));
      expect(res.status).toBe(403);
      expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403 });
    });

    it('không token → 401', async () => {
      expect((await failure(call({}))).status).toBe(401);
    });
  });

  it('SUPER_MANAGER không bị chặn ở cả bốn endpoint', async () => {
    const auth = await chain();
    expect((await client.get('/api/branches/1/closure-impact', auth)).status).toBe(200);
    expect((await failure(resume(1, auth))).status).toBe(409); // ACTIVE không mở lại được: đã qua cổng quyền
  });
});

describe('xem trước tồn đọng (#1)', () => {
  it('chi nhánh 1: đếm lịch BOOKED, kho, nhân viên, Order; điều kiện chặn tạm ngừng rỗng, đóng cửa thì không', async () => {
    const i = await impact(1);
    const booked = bookedOfBranch(1);
    expect(booked.appointments).toBe(3);
    expect(i.bookedAppointments).toBe(booked.appointments + booked.boardings);
    expect(i.stockItemCount).toBeGreaterThan(0);
    expect(i.stockTotalQuantity).toBeGreaterThanOrEqual(i.stockItemCount);
    expect(i.activeStaff).toBeGreaterThanOrEqual(8); // quản lý, 2 lễ tân, 4 bác sĩ, 1 chăm sóc
    // Nguồn chưa đăng ký đều 0; ba nguồn đã nối ở tier3-wiring.ts (đơn online, hoàn tiền, chuyển kho) trả đúng số của mock nguồn.
    expect(i).toMatchObject({ activeBoardingStays: 0, openVisits: 0, openCashierShifts: 0, openCareTasks: 0, openStockCounts: 0 });
    expect(i).toMatchObject({ unfinishedOnlineOrders: openOnlineOrderCount(1), pendingRefunds: pendingRefundCount(1), inTransitTransfers: inTransitTransfersOf(1) });
    expect(i.suspendBlockers).toEqual([]);
    expect(i.closeBlockers.join('|')).toMatch(/tồn kho.*nhân viên/);
    for (const key of Object.keys(i)) {
      if (key.endsWith('Blockers')) expect(Array.isArray((i as unknown as Record<string, unknown>)[key])).toBe(true);
      else expect(Number.isInteger((i as unknown as Record<string, unknown>)[key])).toBe(true);
    }
  });

  it('chi nhánh 2 (DRAFT): 1 quản lý, không lịch hẹn; Order và tồn kho là dữ liệu mẫu của mock bán hàng / kho', async () => {
    expect(await impact(2)).toMatchObject({ bookedAppointments: 0, activeStaff: 1, suspendBlockers: [] });
  });

  it('nguồn đã đăng ký đổi số đếm và điều kiện chặn; khôi phục thì về 0', async () => {
    restoreSources = registerClosureSources({
      openVisits: (id) => (id === 1 ? 2 : 0),
      activeBoardingStays: () => 1,
      openCashierShifts: () => 1,
      unfinishedOnlineOrders: () => 4,
      pendingRefunds: () => 1,
      inTransitTransfers: () => 2,
      openStockCounts: () => 1,
    });
    const i = await impact(1);
    expect(i).toMatchObject({ openVisits: 2, activeBoardingStays: 1, openCashierShifts: 1, unfinishedOnlineOrders: 4, pendingRefunds: 1, inTransitTransfers: 2, openStockCounts: 1 });
    expect(i.suspendBlockers).toHaveLength(3);
    expect(i.closeBlockers.length).toBeGreaterThan(i.suspendBlockers.length + 4);
    expect((await impact(2)).openVisits).toBe(0);
    restoreSources();
    restoreSources = undefined;
    expect((await impact(1)).openVisits).toBe(0);
  });

  it('chi nhánh không tồn tại → 404; id sai kiểu → 400 MALFORMED_REQUEST (trước cả cổng quyền)', async () => {
    const auth = await chain();
    expect((await failure(client.get('/api/branches/999/closure-impact', auth))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404 });
    const bad = await failure(client.get('/api/branches/abc/closure-impact', await as('manager@store1.vn')));
    expect(bad.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', statusCode: 400 });
  });

  it('không ghi gì: gọi hai lần cho cùng kết quả và trạng thái chi nhánh giữ nguyên', async () => {
    const auth = await chain();
    expect(await impact(1, auth)).toEqual(await impact(1, auth));
    expect((await client.get('/api/branches/1', auth)).data.data.status).toBe('ACTIVE');
  });
});

describe('tạm ngừng (#2)', () => {
  const body = { reason: 'Sửa chữa mặt bằng', expectedResumeOn: addDays(localIsoDate(), 14), cancelBookings: true };

  it('thứ tự lỗi: sai kiểu → 403 vai trò → VALIDATION_FAILED → 404', async () => {
    const manager = await as('manager@store1.vn');
    const sm = await chain();
    expect((await failure(suspend(1, { reason: 'x', cancelBookings: 'có' }, manager))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(suspend(1, { reason: 123, cancelBookings: true }, sm))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(suspend(1, { reason: 'x', cancelBookings: true, expectedResumeOn: '2026-02-30' }, sm))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(suspend(1, { reason: 'x', cancelBookings: true, careTaskTargetBranchId: 'a' }, sm))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/branches/1/suspend', [], sm))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(suspend(1, {}, manager))).data.errorCode).toBe('ACCESS_DENIED');

    const missing = await failure(suspend(1, {}, sm));
    expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    expect(missing.data.message).toContain('reason');
    expect(missing.data.message).toContain('cancelBookings');
    expect((await failure(suspend(1, { reason: '   ', cancelBookings: true }, sm))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(suspend(1, { reason: 'x'.repeat(301), cancelBookings: true }, sm))).data.message).toContain('300');
    // Validation chạy trước 404.
    expect((await failure(suspend(999, {}, sm))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(suspend(999, body, sm))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
  });

  it('còn lịch BOOKED mà không chọn hủy hàng loạt → 400 BR-CN-09, không đổi gì', async () => {
    const sm = await chain();
    const res = await failure(suspend(1, { reason: 'Sửa chữa', cancelBookings: false }, sm));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
    expect(res.data.message).toMatch(/\(BR-CN-09\)$/);
    expect((await client.get('/api/branches/1', sm)).data.data.status).toBe('ACTIVE');
    expect((await impact(1)).bookedAppointments).toBeGreaterThan(0);
  });

  it('còn điều kiện chặn → 400 BR-CN-08, message liệt kê; BR-CN-08 đứng trước BR-CN-09', async () => {
    restoreSources = registerClosureSources({ openVisits: () => 2, openCashierShifts: () => 1, activeBoardingStays: () => 3 });
    const res = await failure(suspend(1, { reason: 'Sửa chữa', cancelBookings: false }, await chain()));
    expect(res.data.message).toMatch(/\(BR-CN-08\)$/);
    expect(res.data.message).toContain('2 lượt khám');
    expect(res.data.message).toContain('1 ca thu ngân');
    expect(res.data.message).toContain('3 thú');
    // Tạm ngừng KHÔNG bị chặn bởi kho / nhân viên (chỉ đóng cửa mới bị).
    expect(res.data.message).not.toContain('tồn kho');
  });

  it('thành công: SUSPENDED, hủy hàng loạt lịch, ẩn khỏi trang công khai, chi nhánh trả về không lộ cột vòng đời', async () => {
    const sm = await chain();
    const before = (await client.get('/api/public/branches')).data.data as { branchId: number }[];
    expect(before.map((b) => b.branchId)).toContain(1);

    const res = await suspend(1, body, sm);
    expect(res.status).toBe(200);
    const result = life(res);
    expect(result).toMatchObject({ branchId: 1, status: 'SUSPENDED', expectedResumeOn: body.expectedResumeOn, closedAt: null, reason: body.reason });
    expect(Date.parse(result.suspendedAt!)).not.toBeNaN();

    expect(bookedOfBranch(1)).toEqual({ appointments: 0, boardings: 0 });
    expect((await impact(1)).bookedAppointments).toBe(0);
    const detail = (await client.get('/api/branches/1', sm)).data.data;
    expect(detail.status).toBe('SUSPENDED');
    expect(Object.keys(detail).sort()).toEqual(['acceptsAfterHoursEmergency', 'activatedAt', 'address', 'branchId', 'latitude', 'longitude', 'name', 'phone', 'status']);
    const filtered = (await client.get('/api/branches', { ...sm, params: { status: 'SUSPENDED' } })).data.data as { branchId: number }[];
    expect(filtered.map((b) => b.branchId)).toEqual([1]);
    expect((await client.get('/api/public/branches')).data.data.map((b: { branchId: number }) => b.branchId)).not.toContain(1);
    expect((await failure(client.get('/api/public/branches/1'))).status).toBe(404);
  });

  it('chi nhánh không còn nhận đặt lịch / đặt chỗ (BR-CN-10), mở lại thì nhận lại', async () => {
    const sm = await chain();
    const date = addDays(localIsoDate(), 3);
    const slots = (auth: object) => client.get('/api/branches/1/available-slots', { ...auth, params: { serviceId: 1, date } });
    const stay = (auth: object) =>
      client.get('/api/branches/1/boarding-availability', { ...auth, params: { kennelTypeId: 6, checkInDate: addDays(date, 1), checkOutDate: addDays(date, 2) } });
    const customer = await as('khach@petcare.vn');
    const desk = await as('reception@store1.vn');
    expect((await slots(customer)).status).toBe(200);

    await suspend(1, body, sm);
    expect((await failure(slots(customer))).status).toBe(404);
    const refused = await failure(slots(desk));
    expect(refused.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chi nhánh chưa nhận đặt lịch (BR-LH-01)' });
    expect((await failure(stay(customer))).status).toBe(404);

    await resume(1, sm);
    expect((await slots(customer)).status).toBe(200);
  });

  it('409 khi chi nhánh DRAFT, đã tạm ngừng hoặc đã đóng cửa', async () => {
    const sm = await chain();
    await force({ 3: 'SUSPENDED' });
    const draft = await failure(suspend(2, body, sm));
    expect(draft.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    expect(draft.data.message).toContain('DRAFT');
    expect((await failure(suspend(3, { reason: 'x', cancelBookings: false }, sm))).status).toBe(409);
    await force({ 3: 'CLOSED' });
    expect((await failure(suspend(3, { reason: 'x', cancelBookings: false }, sm))).status).toBe(409);
  });

  it('Care Task OPEN: chuyển sang chi nhánh ACTIVE khác, hoặc hủy kèm lý do khi không chọn; chi nhánh nhận sai → 400 BR-CN-09', async () => {
    const reassign = vi.fn();
    restoreSources = registerClosureSources({ reassignCareTasks: reassign });
    const sm = await chain();
    await force({ 3: 'ACTIVE' });
    const base = { reason: 'Sửa chữa', cancelBookings: true };

    for (const target of [1, 2, 999]) {
      const bad = await failure(suspend(1, { ...base, careTaskTargetBranchId: target }, sm));
      expect(bad.data.message).toMatch(/\(BR-CN-09\)$/);
    }
    expect(reassign).not.toHaveBeenCalled();

    await suspend(1, { ...base, careTaskTargetBranchId: 3 }, sm);
    expect(reassign).toHaveBeenLastCalledWith(1, 3, 'Sửa chữa');
    await resume(1, sm);
    await suspend(1, base, sm);
    expect(reassign).toHaveBeenLastCalledWith(1, null, 'Sửa chữa');
  });

  it('cắt khoảng trắng đầu cuối của lý do; ngày dự kiến mở lại tùy chọn', async () => {
    const res = await suspend(1, { reason: '  Nghỉ tết  ', cancelBookings: true }, await chain());
    expect(life(res)).toMatchObject({ reason: 'Nghỉ tết', expectedResumeOn: null });
  });
});

describe('mở lại (#3)', () => {
  const body = { reason: 'Sửa chữa', cancelBookings: true };

  it('thành công: về ACTIVE, xóa cột tạm ngừng, hiện lại ở trang công khai', async () => {
    const sm = await chain();
    await suspend(1, { ...body, expectedResumeOn: addDays(localIsoDate(), 7) }, sm);
    const res = await resume(1, sm);
    expect(res.status).toBe(200);
    expect(life(res)).toEqual({ branchId: 1, status: 'ACTIVE', suspendedAt: null, expectedResumeOn: null, closedAt: null, reason: null });
    expect((await client.get('/api/public/branches')).data.data.map((b: { branchId: number }) => b.branchId)).toContain(1);
    // Lịch đã hủy không tự trở lại.
    expect((await impact(1)).bookedAppointments).toBe(0);
  });

  it('409 khi chi nhánh đang ACTIVE hoặc đã CLOSED; 404; 400 sai kiểu', async () => {
    const sm = await chain();
    expect((await failure(resume(1, sm))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    await force({ 1: 'CLOSED' });
    expect((await failure(resume(1, sm))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await failure(resume(999, sm))).status).toBe(404);
    expect((await failure(client.post('/api/branches/x/resume', undefined, sm))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('400 BR-CN-01 khi chưa có giờ mở cửa, BR-QT-04 khi chưa có quản lý đang hoạt động (BR-QT-04 nêu trước nếu thiếu cả hai)', async () => {
    const sm = await chain();
    await force({ 2: 'SUSPENDED', 3: 'SUSPENDED' });
    const noHours = await failure(resume(2, sm)); // chi nhánh 2 có quản lý nhưng chưa cấu hình giờ
    expect(noHours.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION' });
    expect(noHours.data.message).toMatch(/giờ mở cửa.*\(BR-CN-01\)$/);
    const noManager = await failure(resume(3, sm)); // chi nhánh 3 thiếu cả quản lý lẫn giờ
    expect(noManager.data.message).toMatch(/BRANCH_MANAGER.*giờ mở cửa.*\(BR-QT-04\)$/);
    expect((await client.get('/api/branches/3', sm)).data.data.status).toBe('SUSPENDED');
  });

  it('chi nhánh 2 cấu hình giờ xong thì mở lại được', async () => {
    const sm = await chain();
    const manager2 = await as('manager2@store2.vn');
    const days = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, ranges: [{ open: '08:00', close: '17:00' }] }));
    await client.put('/api/branches/2/opening-hours', { effectiveFrom: localIsoDate(), days }, manager2);
    await force({ 2: 'SUSPENDED' });
    // `force` chép lại danh sách chi nhánh; giờ vừa lưu nằm ở bảng `hours` nên phải còn nguyên sau khi ép trạng thái.
    expect(life(await resume(2, sm))).toMatchObject({ branchId: 2, status: 'ACTIVE' });
  });
});

describe('đóng cửa (#4)', () => {
  const body = { reason: 'Hết hợp đồng thuê mặt bằng', cancelBookings: true };

  it('chi nhánh 1 còn tồn kho và nhân viên → 400 BR-CN-08 liệt kê cả hai; tạm ngừng rồi vẫn không đóng được', async () => {
    const sm = await chain();
    const res = await failure(close(1, body, sm));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
    expect(res.data.message).toMatch(/tồn kho/);
    expect(res.data.message).toMatch(/nhân viên/);
    expect(res.data.message).toMatch(/\(BR-CN-08\)$/);

    await suspend(1, body, sm);
    expect((await failure(close(1, body, sm))).data.message).toMatch(/\(BR-CN-08\)$/);
    expect((await client.get('/api/branches/1', sm)).data.data.status).toBe('SUSPENDED');
  });

  it('điều kiện chặn của nguồn đăng ký vào message đóng cửa', async () => {
    restoreSources = registerClosureSources({ unfinishedOnlineOrders: () => 2, pendingRefunds: () => 1, inTransitTransfers: () => 1, openStockCounts: () => 1 });
    const res = await failure(close(1, body, await chain()));
    for (const part of ['2 đơn online', '1 khoản hoàn tiền', '1 phiếu chuyển kho', '1 phiên kiểm kê']) expect(res.data.message).toContain(part);
  });

  it('chi nhánh sạch (3): đóng từ ACTIVE → CLOSED, giữ lý do, không mở lại được', async () => {
    const sm = await chain();
    await force({ 3: 'ACTIVE' });
    const res = await close(3, { reason: '  Hết hợp đồng  ', cancelBookings: false }, sm);
    expect(res.status).toBe(200);
    expect(life(res)).toMatchObject({ branchId: 3, status: 'CLOSED', reason: 'Hết hợp đồng', expectedResumeOn: null, suspendedAt: null });
    expect(Date.parse(life(res).closedAt!)).not.toBeNaN();

    expect((await failure(resume(3, sm))).data.errorCode).toBe('BUSINESS_RULE_VIOLATION'); // thiếu quản lý (guard 400 chạy trước 409)
    expect((await failure(close(3, body, sm))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    expect((await failure(suspend(3, body, sm))).status).toBe(409);
    const closed = (await client.get('/api/branches', { ...(await chain()), params: { status: 'CLOSED' } })).data.data as { branchId: number }[];
    expect(closed.map((b) => b.branchId)).toEqual([3]);
  });

  it('đóng từ SUSPENDED giữ ngày tạm ngừng cũ và bỏ ngày dự kiến mở lại', async () => {
    const sm = await chain();
    await force({ 3: 'ACTIVE' });
    const suspended = life(await suspend(3, { reason: 'Sửa', cancelBookings: false, expectedResumeOn: addDays(localIsoDate(), 5) }, sm));
    const closed = life(await close(3, { reason: 'Không mở lại nữa', cancelBookings: false }, sm));
    expect(closed).toMatchObject({ status: 'CLOSED', suspendedAt: suspended.suspendedAt, expectedResumeOn: null, reason: 'Không mở lại nữa' });
  });

  it('409 khi DRAFT; validation như tạm ngừng', async () => {
    const sm = await chain();
    expect((await failure(close(3, body, sm))).data.errorCode).toBe('INVALID_STATE_TRANSITION'); // chi nhánh 2 còn quản lý nên ra BR-CN-08 trước
    expect((await failure(close(2, body, sm))).data.message).toMatch(/\(BR-CN-08\)$/);
    expect((await failure(close(2, { cancelBookings: true }, sm))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(close(999, body, sm))).status).toBe(404);
    // `expectedResumeOn` không thuộc CloseBranchRequest: gửi thừa thì bỏ qua như Jackson, kể cả sai định dạng.
    await force({ 3: 'ACTIVE' });
    expect((await close(3, { ...body, expectedResumeOn: 'không phải ngày' }, sm)).status).toBe(200);
  });
});

describe('state cũ trong localStorage', () => {
  it('state chỉ có {} hoặc thiếu bảng lifecycle vẫn chạy cả bốn route', async () => {
    const sm = await chain();
    localStorage.setItem('petcare-mock-branch', '{}');
    expect((await client.get('/api/branches/1/closure-impact', sm)).status).toBe(200);
    expect(life(await suspend(1, { reason: 'Thử', cancelBookings: true }, sm)).status).toBe('SUSPENDED');

    const stored = JSON.parse(localStorage.getItem('petcare-mock-branch')!) as Record<string, unknown>;
    delete stored.lifecycle;
    localStorage.setItem('petcare-mock-branch', JSON.stringify(stored));
    expect(life(await resume(1, sm))).toMatchObject({ status: 'ACTIVE', suspendedAt: null });
    expect(life(await suspend(1, { reason: 'Lại', cancelBookings: false }, sm)).reason).toBe('Lại');
  });
});
