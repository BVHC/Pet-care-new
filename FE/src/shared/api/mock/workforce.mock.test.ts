import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, pinClock } from '../../../test/boarding-harness';
import type { PageResponse } from '../../types';
import type { AppNotification } from '../../types/care';
import type { ShiftAssignment, ShiftDefinition, ShiftRegistration, ShiftRequest, ShiftWeeklySummary } from '../../types/workforce';
import { addDays } from '../../utils/branch-schedule';
import { ruleIdOf } from '../api-error';
import { staffLeftBranch } from './workforce.mock';
import { installMockApi } from './mock-api';

// Nhân sự (workforce-v1 #1–17) — mock khớp số đo trên BE thật 8083 (bản sao petcare_t66, 23/11/2026); chỗ BE im lặng theo W-chú thích ở đầu workforce.mock.ts. Đồng hồ ghim ở thứ Sáu 23/10/2026 09:00 giờ Việt Nam (ca sáng 07:30 hôm nay
// đã bắt đầu, ca chiều 13:00 chưa). Dữ liệu mẫu (ngày tương đối với hôm nay T): nhân viên 4 (reception), 5 (doctor), 6 (caretaker), 21 (doctor2) có lịch T−7…T+10 luân phiên ca sáng (#1) / ca chiều (#2);
// nhân viên 22 (doctor3) và 23 (doctor4) chưa có phân ca nào. Yêu cầu mẫu: #1 nhân viên 5 xin NGHỈ T+3 (PENDING), #2 nhân viên 6 xin ĐỔI sang ca chiều T+4 (PENDING), #3 nhân viên 4 nghỉ T+5 (APPROVED →
// ON_LEAVE), #4 nhân viên 21 T+6 (REJECTED), #5 nhân viên 5 T+7 (WITHDRAWN), #6 nhân viên 4 T+8 (REJECTED do hệ thống, phân ca CANCELLED); nhân viên 4 ABSENT ở T−2.
// Chi nhánh 1: ca #1 Ca sáng 07:30–12:00, #2 Ca chiều 13:00–17:30, #3 Ca hành chính 08:00–17:00, #4 Ca tối 17:30–21:00, #5 Ca đêm cũ (ngừng dùng); chi nhánh 2: ca #6, #7 (không nhân viên).

const TODAY = '2026-10-23';
const day = (offset: number) => addDays(TODAY, offset);
const EMAIL = { ...ACCOUNTS, doctor: 'doctor@store1.vn', doctor2: 'doctor2@store1.vn', doctor3: 'doctor3@store1.vn', doctor4: 'doctor4@store1.vn', newstaff: 'newstaff@store1.vn' } as const;
type Who = keyof typeof EMAIL;

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = axios.create();
  installMockApi(client);
});
afterEach(() => vi.useRealTimers());

const send = async (who: Who, method: string, url: string, body?: unknown, params?: object) =>
  client.request({ method, url, data: body, params, ...(await authAs(client, EMAIL[who])) });
const get = async <T>(who: Who, url: string, params?: object) => (await send(who, 'get', url, undefined, params)).data.data as T;
const post = async <T>(who: Who, url: string, body?: unknown) => (await send(who, 'post', url, body)).data.data as T;
const rejected = (who: Who, method: string, url: string, body?: unknown, params?: object) => failure(send(who, method, url, body, params));
/** Mã trạng thái của một request, thành công hay lỗi. */
const statusOf = async (request: Promise<AxiosResponse>) => (await request.catch((e) => e.response as AxiosResponse)).status;

function expectFail(res: AxiosResponse, status: number, errorCode: string, message?: string | RegExp) {
  expect(res.status).toBe(status);
  expect(res.data).toMatchObject({ success: false, errorCode, statusCode: status });
  expect(Object.keys(res.data).sort()).toEqual(['errorCode', 'message', 'statusCode', 'success', 'timestamp', 'traceId']);
  if (typeof message === 'string') expect(res.data.message).toBe(message);
  else if (message) expect(res.data.message).toMatch(message);
}
const ruleFailure = (res: AxiosResponse, ruleId: string, message?: string | RegExp) => {
  expectFail(res, 400, 'BUSINESS_RULE_VIOLATION', message);
  expect(ruleIdOf(res.data.message)).toBe(ruleId);
};

const definitions = (who: Who = 'manager', params: object = {}) => get<ShiftDefinition[]>(who, '/api/shift-definitions', params);
const assignments = async (params: object = {}, who: Who = 'manager') =>
  (await get<PageResponse<ShiftAssignment>>(who, '/api/shift-assignments', { from: day(-9), to: day(11), size: 100, ...params })).content;
const mine = (who: Who, from = day(-9), to = day(11)) => get<ShiftAssignment[]>(who, '/api/me/shift-assignments', { from, to });
const requestsOf = async (who: Who, params: object = {}) => (await get<PageResponse<ShiftRequest>>(who, '/api/me/shift-requests', params)).content;
const pendingList = async (params: object = {}) => (await get<PageResponse<ShiftRequest>>('manager', '/api/shift-requests', params)).content;
const notices = async (who: Who) => (await get<PageResponse<AppNotification>>(who, '/api/me/notifications', { size: 100 })).content;

async function assignmentOf(staffId: number, workDate: string): Promise<ShiftAssignment> {
  const found = (await assignments({ staffId })).find((a) => a.workDate === workDate);
  if (!found) throw new Error(`Không có phân ca của nhân viên ${staffId} ngày ${workDate}`);
  return found;
}
const assign = (staffId: number, shiftDefinitionId: number, workDate: string, who: Who = 'manager') =>
  post<ShiftAssignment>(who, '/api/shift-assignments', { staffId, shiftDefinitionId, workDate });
const newShift = (name: string, startTime: string, endTime: string) => post<ShiftDefinition>('manager', '/api/shift-definitions', { name, startTime, endTime });

// ─── Ca làm việc (#1–3) ───

describe('ca làm việc (#1)', () => {
  it('chi nhánh của người gọi, sắp theo giờ bắt đầu, cả ca ngừng dùng; activeOnly lọc ca đang dùng', async () => {
    const all = await definitions();
    expect(all.map((d) => [d.name, d.startTime, d.endTime, d.active])).toEqual([
      ['Ca sáng', '07:30', '12:00', true],
      ['Ca hành chính', '08:00', '17:00', true],
      ['Ca chiều', '13:00', '17:30', true],
      ['Ca tối', '17:30', '21:00', true],
      ['Ca đêm cũ', '21:00', '23:00', false],
    ]);
    expect(all.every((d) => d.branchId === 1)).toBe(true);
    expect(Object.keys(all[0]).sort()).toEqual(['active', 'branchId', 'endTime', 'name', 'shiftDefinitionId', 'startTime']);
    expect((await definitions('manager', { activeOnly: true })).map((d) => d.name)).not.toContain('Ca đêm cũ');
    expect((await definitions('manager', { activeOnly: false })).length).toBe(5);
    // Chi nhánh 2 chỉ thấy ca của mình, tên có thể trùng chi nhánh khác.
    expect((await definitions('manager2')).map((d) => [d.shiftDefinitionId, d.name, d.branchId])).toEqual([[6, 'Ca sáng', 2], [7, 'Ca chiều', 2]]);
  });

  it('activeOnly sai kiểu → 400 MALFORMED_REQUEST, đến TRƯỚC kiểm vai trò', async () => {
    expectFail(await rejected('manager', 'get', '/api/shift-definitions', undefined, { activeOnly: 'maybe' }), 400, 'MALFORMED_REQUEST', "Tham số 'activeOnly' sai kiểu dữ liệu");
    expectFail(await rejected('manager', 'get', '/api/shift-definitions', undefined, { activeOnly: 'constructor' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'get', '/api/shift-definitions', undefined, { activeOnly: 'maybe' }), 400, 'MALFORMED_REQUEST');
  });
});

describe('ca làm việc cho A06–A08 (#1, A10)', () => {
  it('lễ tân, bác sĩ, chăm sóc gọi được: chỉ ca đang dùng của chi nhánh mình, activeOnly bị bỏ qua; ADMIN, SUPER_MANAGER, khách vẫn 403', async () => {
    for (const who of ['desk', 'vet', 'caretaker'] as const) {
      expect((await definitions(who)).map((d) => d.name)).toEqual(['Ca sáng', 'Ca hành chính', 'Ca chiều', 'Ca tối']);
      expect((await definitions(who, { activeOnly: false })).map((d) => d.name)).not.toContain('Ca đêm cũ');
    }
    for (const who of ['admin', 'chain', 'customer'] as const) expectFail(await rejected(who, 'get', '/api/shift-definitions'), 403, 'ACCESS_DENIED');
  });
});

describe('tạo ca (#2, BR-NS-01)', () => {
  it('201, cắt khoảng trắng tên, đang dùng, chỉ thấy ở chi nhánh của mình', async () => {
    const res = await send('manager', 'post', '/api/shift-definitions', { name: '  Ca đặc biệt ', startTime: '09:00', endTime: '10:30' });
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: expect.any(String), code: 201, data: { name: 'Ca đặc biệt', startTime: '09:00', endTime: '10:30', active: true, branchId: 1 } });
    expect((await definitions('manager')).map((d) => d.name)).toContain('Ca đặc biệt');
    expect((await definitions('manager2')).map((d) => d.name)).not.toContain('Ca đặc biệt');
  });

  it('tên trùng trong chi nhánh (không phân biệt hoa thường, cả ca ngừng dùng) → 400 BR-NS-01; chi nhánh khác thì được', async () => {
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'CA SÁNG', startTime: '06:00', endTime: '07:00' }), 'BR-NS-01', /đã tồn tại/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'ca đêm cũ', startTime: '06:00', endTime: '07:00' }), 'BR-NS-01');
    expect((await send('manager2', 'post', '/api/shift-definitions', { name: 'Ca tối', startTime: '17:30', endTime: '21:00' })).status).toBe(201);
  });

  it('giờ bắt đầu không nhỏ hơn giờ kết thúc → 400 BR-NS-01; giờ sai được báo TRƯỚC tên trùng (đo BE)', async () => {
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lạ', startTime: '12:00', endTime: '12:00' }), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lạ', startTime: '18:00', endTime: '06:00' }), 'BR-NS-01');
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca sáng', startTime: '18:00', endTime: '06:00' }), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
  });

  it('VALIDATION_FAILED: thiếu / trống / quá 60 ký tự, câu báo của BE', async () => {
    expectFail(
      await rejected('manager', 'post', '/api/shift-definitions', {}),
      400,
      'VALIDATION_FAILED',
      'name: Vui lòng nhập tên ca; startTime: Vui lòng nhập giờ bắt đầu; endTime: Vui lòng nhập giờ kết thúc',
    );
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: '   ', startTime: '08:00', endTime: '09:00' }), 400, 'VALIDATION_FAILED', 'name: Vui lòng nhập tên ca');
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 'x'.repeat(61), startTime: '08:00', endTime: '09:00' }), 400, 'VALIDATION_FAILED', 'name: Tên ca tối đa 60 ký tự');
    expect((await send('manager', 'post', '/api/shift-definitions', { name: 'x'.repeat(60), startTime: '08:00', endTime: '09:00' })).status).toBe(201);
    // Giờ rỗng / toàn khoảng trắng đọc như vắng (BE: @NotNull).
    for (const blank of [' ', '', null]) {
      expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lỗi', startTime: blank, endTime: '23:59' }), 400, 'VALIDATION_FAILED', 'startTime: Vui lòng nhập giờ bắt đầu');
    }
    expect((await send('manager', 'post', '/api/shift-definitions', { name: 'Ca cuối ngày', startTime: '00:00', endTime: '23:59' })).status).toBe(201);
  });

  it('giờ sai dạng (8:30, 08:60, 25:00, 08:00:00, 08-30) → 400 MALFORMED_REQUEST như BE; riêng 24:00 BE nhận thành 00:00', async () => {
    for (const bad of ['8:30', '08:60', '25:00', '29:30', '08-30', '08:00:00']) {
      expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lỗi', startTime: bad, endTime: '23:59' }), 400, 'MALFORMED_REQUEST');
    }
    const res = await send('manager', 'post', '/api/shift-definitions', { name: 'Ca nửa đêm', startTime: '24:00', endTime: '06:00' });
    expect(res.data.data).toMatchObject({ startTime: '00:00', endTime: '06:00' });
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lỗi 24', startTime: '08:00', endTime: '24:00' }), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
  });

  it('MALFORMED_REQUEST và VALIDATION_FAILED đều đến TRƯỚC vai trò (đo BE: @Valid chạy khi nạp tham số, @PreAuthorize sau đó)', async () => {
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 123, startTime: '08:00', endTime: '09:00' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', '[1,2]'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-definitions'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/shift-definitions', { name: 123 }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/shift-definitions', {}), 400, 'VALIDATION_FAILED');
    expectFail(await rejected('desk', 'post', '/api/shift-definitions', { name: 'x' }), 400, 'VALIDATION_FAILED');
    expectFail(await rejected('desk', 'post', '/api/shift-definitions', { name: 'Ca lạ', startTime: '08:00', endTime: '09:00' }), 403, 'ACCESS_DENIED');
  });
});

describe('sửa / ngừng dùng ca (#3, BR-NS-01)', () => {
  const patch = (who: Who, id: number | string, body: unknown) => send(who, 'patch', `/api/shift-definitions/${id}`, body);

  it('chỉ trường có trong body được đổi; body {} hoặc toàn null → 400 VALIDATION_FAILED (đo BE); đổi tên, không đổi giờ ca đã có phân ca', async () => {
    const before = (await definitions()).find((d) => d.shiftDefinitionId === 1)!;
    const none = 'notEmpty: Cần gửi ít nhất một trường cần sửa (name, startTime, endTime, active)';
    expectFail(await failure(patch('manager', 1, {})), 400, 'VALIDATION_FAILED', none);
    expectFail(await failure(patch('manager', 1, { name: null, startTime: null, endTime: null, active: null })), 400, 'VALIDATION_FAILED', none);
    expectFail(await failure(patch('desk', 1, {})), 400, 'VALIDATION_FAILED', none); // trước vai trò
    expect((await patch('manager', 1, { name: null, active: null, endTime: '12:00' })).data.data).toEqual(before);
    expect((await patch('manager', 1, { name: ' Ca sáng sớm ' })).data.data).toEqual({ ...before, name: 'Ca sáng sớm' });
    // Gửi lại đúng giờ hiện tại coi như không đổi giờ.
    expect((await patch('manager', 1, { startTime: '07:30', endTime: '12:00' })).status).toBe(200);
  });

  it('sửa giờ ca đã có phân ca → 400 BR-NS-01; ca chưa có phân ca thì sửa được', async () => {
    ruleFailure((await failure(patch('manager', 1, { startTime: '07:00' }))) as AxiosResponse, 'BR-NS-01', 'Ca đã có phân ca, không đổi được giờ; hãy tạo ca mới (BR-NS-01)');
    const fresh = await newShift('Ca mới', '09:00', '10:00');
    const res = await patch('manager', fresh.shiftDefinitionId, { startTime: '08:30', endTime: '11:00' });
    expect(res.data.data).toMatchObject({ startTime: '08:30', endTime: '11:00' });
  });

  it('giờ sau khi sửa vẫn phải bắt đầu < kết thúc; tên không được trùng ca khác (nhưng giữ tên của chính nó thì được)', async () => {
    const fresh = await newShift('Ca mới', '09:00', '10:00');
    ruleFailure(await failure(patch('manager', fresh.shiftDefinitionId, { startTime: '10:00' })), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
    ruleFailure(await failure(patch('manager', fresh.shiftDefinitionId, { endTime: '08:00' })), 'BR-NS-01');
    ruleFailure(await failure(patch('manager', fresh.shiftDefinitionId, { name: 'CA CHIỀU' })), 'BR-NS-01', /đã tồn tại/);
    expect((await patch('manager', fresh.shiftDefinitionId, { name: 'ca mới' })).status).toBe(200);
  });

  it('thứ tự kiểm BR-NS-01 (đo BE): giờ sai → tên trùng → sửa giờ khi đã có phân ca → ngừng dùng khi còn phân ca', async () => {
    // Ca #1 (07:30–12:00) có phân ca; "Ca chiều" là tên của ca #2.
    ruleFailure(await failure(patch('manager', 1, { name: 'Ca chiều', startTime: '13:00' })), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
    ruleFailure(await failure(patch('manager', 1, { name: 'Ca chiều', startTime: '07:00' })), 'BR-NS-01', /đã tồn tại/);
    ruleFailure(await failure(patch('manager', 1, { active: false, startTime: '13:00' })), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
    ruleFailure(await failure(patch('manager', 1, { active: false, startTime: '07:00' })), 'BR-NS-01', /không đổi được giờ/);
  });

  it('ngừng dùng: bị chặn khi còn phân ca SCHEDULED từ hôm nay; không bị chặn khi chỉ còn phân ca đã qua / đã hủy; bật lại được', async () => {
    ruleFailure(await failure(patch('manager', 1, { active: false })), 'BR-NS-01', 'Ca còn phân ca từ hôm nay trở đi, không ngừng dùng được (BR-NS-01)');
    const fresh = await newShift('Ca tạm', '09:00', '10:00');
    expect((await patch('manager', fresh.shiftDefinitionId, { active: false })).data.data.active).toBe(false);
    expect((await patch('manager', fresh.shiftDefinitionId, { active: true })).data.data.active).toBe(true);
    // Ca có phân ca SCHEDULED bị hủy hết thì ngừng dùng được.
    const a = await assign(22, fresh.shiftDefinitionId, day(2));
    ruleFailure(await failure(patch('manager', fresh.shiftDefinitionId, { active: false })), 'BR-NS-01');
    await post('manager', `/api/shift-assignments/${a.shiftAssignmentId}/cancel`, { reason: 'Đổi kế hoạch' });
    expect((await patch('manager', fresh.shiftDefinitionId, { active: false })).status).toBe(200);
  });

  it('404 khi không có, 403 phạm vi khi của chi nhánh khác; VALIDATION_FAILED đến trước phạm vi; id sai kiểu / active sai kiểu → MALFORMED_REQUEST', async () => {
    expectFail(await failure(patch('manager', 999, { name: 'x' })), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(patch('manager2', 1, { name: 'Ca trộm' })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
    expectFail(await failure(patch('manager2', 1, { name: '' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { name: ' ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { name: 'x'.repeat(61) })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { startTime: '25:00' })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(patch('manager', 'abc', {})), 400, 'MALFORMED_REQUEST', "Tham số 'shiftDefinitionId' sai kiểu dữ liệu");
    expectFail(await failure(patch('customer', 'abc', {})), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(patch('customer', 1, { name: 'Ca trộm' })), 403, 'ACCESS_DENIED');
  });
});

// ─── Lịch phân ca của chi nhánh (#4) ───

describe('lịch phân ca của chi nhánh (#4, BR-NS-03)', () => {
  it('sắp workDate, startTime, id tăng dần; chỉ chi nhánh của mình; trường đúng schema', async () => {
    const rows = await assignments();
    expect(rows.length).toBeGreaterThan(40);
    const keys = rows.map((a) => `${a.workDate} ${a.startTime}`);
    expect(keys).toEqual([...keys].sort());
    for (let i = 1; i < rows.length; i++) {
      if (keys[i] === keys[i - 1]) expect(rows[i].shiftAssignmentId).toBeGreaterThan(rows[i - 1].shiftAssignmentId);
    }
    expect(rows.every((a) => a.branchId === 1 && a.workDate >= day(-9) && a.workDate <= day(11))).toBe(true);
    expect(Object.keys(rows[0]).sort()).toEqual(
      ['branchId', 'endTime', 'pendingRequestId', 'reason', 'shiftAssignmentId', 'shiftDefinitionId', 'shiftName', 'source', 'staffId', 'staffName', 'startTime', 'status', 'workDate'],
    );
    expect(await assignments({}, 'manager2')).toEqual([]);
    // Không gửi size: mặc định 20 dòng mỗi trang.
    const byDefault = await get<PageResponse<ShiftAssignment>>('manager', '/api/shift-assignments', { from: day(-9), to: day(11) });
    expect(byDefault).toMatchObject({ page: 0, size: 20, totalElements: rows.length });
    expect(byDefault.content).toHaveLength(20);
  });

  it('lọc staffId, status; phân trang page / size; pendingRequestId của phân ca có yêu cầu chờ', async () => {
    const of5 = await assignments({ staffId: 5 });
    expect(of5.length).toBeGreaterThan(5);
    expect(of5.every((a) => a.staffId === 5 && a.staffName === 'BS. Minh Anh')).toBe(true);
    expect((await assignments({ status: 'ABSENT' })).map((a) => [a.staffId, a.workDate, a.reason])).toEqual([[4, day(-2), 'Nghỉ ốm không báo trước.']]);
    expect((await assignments({ status: 'ON_LEAVE' })).map((a) => [a.staffId, a.workDate])).toEqual([[4, day(5)]]);
    expect((await assignments({ status: 'CANCELLED' })).map((a) => [a.staffId, a.workDate])).toEqual([[4, day(8)]]);
    expect((await assignments({ staffId: 99 })).length).toBe(0);
    const withPending = (await assignments()).filter((a) => a.pendingRequestId !== null).map((a) => [a.staffId, a.workDate, a.pendingRequestId]);
    expect(withPending).toEqual([[5, day(3), 1], [6, day(4), 2]]);
    const page = await get<PageResponse<ShiftAssignment>>('manager', '/api/shift-assignments', { from: day(0), to: day(6), size: 5, page: 1 });
    expect(page).toMatchObject({ page: 1, size: 5 });
    expect(page.content).toHaveLength(5);
    expect(page.totalPages).toBe(Math.ceil(page.totalElements / 5));
    const clamped = await get<PageResponse<ShiftAssignment>>('manager', '/api/shift-assignments', { from: day(0), to: day(6), size: 500, page: -1 });
    expect(clamped).toMatchObject({ page: 0, size: 100 });
  });

  it('from / to thiếu hoặc sai kiểu → 400 MALFORMED_REQUEST (trước vai trò); to < from và khoảng quá 62 ngày (tính cả hai đầu) → 400 BR-NS-03', async () => {
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { to: day(1) }), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'from'");
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0) }), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'to'");
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0), to: '' }), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'to'");
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: '2026-02-30', to: day(1) }), 400, 'MALFORMED_REQUEST', "Tham số 'from' sai kiểu dữ liệu");
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(1), staffId: 'x' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(1), status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(1), page: 'a' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'get', '/api/shift-assignments', undefined, { to: day(1) }), 400, 'MALFORMED_REQUEST');
    ruleFailure(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(1), to: day(0) }), 'BR-NS-03');
    expect((await get<PageResponse<ShiftAssignment>>('manager', '/api/shift-assignments', { from: day(0), to: day(0) })).page).toBe(0);
    expect((await get<PageResponse<ShiftAssignment>>('manager', '/api/shift-assignments', { from: day(0), to: day(61) })).page).toBe(0);
    ruleFailure(await rejected('manager', 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(62) }), 'BR-NS-03', /62 ngày/);
    expectFail(await rejected('chain', 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(62) }), 403, 'ACCESS_DENIED');
  });
});

// ─── Phân ca (#5) ───

describe('phân ca (#5, BR-NS-02)', () => {
  it('201: nhân viên đang hoạt động của chi nhánh, SCHEDULED, hiện ở lịch của chính họ; hôm nay cũng được', async () => {
    const res = await send('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(2) });
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({
      branchId: 1, staffId: 22, staffName: 'BS. Ngọc Lan', shiftDefinitionId: 1, shiftName: 'Ca sáng', startTime: '07:30', endTime: '12:00', workDate: day(2),
      status: 'SCHEDULED', source: 'MANAGER', reason: null, pendingRequestId: null,
    });
    expect((await mine('doctor3')).map((a) => a.shiftAssignmentId)).toEqual([res.data.data.shiftAssignmentId]);
    expect((await assign(22, 2, day(0))).workDate).toBe(day(0));
    // Mọi vai A06–A08 được phân: lễ tân mới (8), chăm sóc (6) cũng vậy.
    expect((await assign(8, 1, day(3))).staffName).toBe('Lễ tân mới');
  });

  it('trùng đúng cặp ca – ngày, chồng giờ phân ca khác trong ngày → 400 BR-NS-02 nêu ca đang chồng; chạm đầu mút thì không chồng; ngày khác thì được', async () => {
    await assign(22, 1, day(2)); // 07:30–12:00
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(2) }), 'BR-NS-02', 'Nhân viên đã có phân ca trùng hoặc chồng giờ: Ca sáng (07:30–12:00) (BR-NS-02)');
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 3, workDate: day(2) }), 'BR-NS-02', 'Nhân viên đã có phân ca trùng hoặc chồng giờ: Ca sáng (07:30–12:00) (BR-NS-02)');
    await assign(22, 2, day(2)); // 13:00–17:30, không chồng
    await assign(22, 4, day(2)); // 17:30–21:00 chạm đầu mút ca chiều
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 3, workDate: day(2) }), 'BR-NS-02', /trùng hoặc chồng giờ: /);
    expect((await assign(22, 3, day(3))).shiftName).toBe('Ca hành chính');
    // Phân ca đã hủy không còn tính khi kiểm chồng giờ.
    const sang = await assignmentOf(22, day(2));
    expect(sang.shiftName).toBe('Ca sáng');
    await post('manager', `/api/shift-assignments/${sang.shiftAssignmentId}/cancel`, { reason: 'Đổi lịch' });
    expect((await assign(22, 1, day(2))).status).toBe('SCHEDULED');
  });

  it('ngày quá khứ, ca ngừng dùng, người không phải A06–A08 → 400 BR-NS-02', async () => {
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(-1) }), 'BR-NS-02', /quá khứ/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 5, workDate: day(1) }), 'BR-NS-02', 'Ca đã ngừng dùng, không phân ca được (BR-NS-02)');
    // Quản lý chính chi nhánh (A05) thuộc chi nhánh nhưng không phải A06–A08.
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 3, shiftDefinitionId: 1, workDate: day(1) }), 'BR-NS-02', 'Chỉ phân ca cho lễ tân, bác sĩ hoặc nhân viên chăm sóc đang hoạt động của chi nhánh (BR-NS-02)');
  });

  it('thứ tự kiểm (đo BE): 404 nhân viên → 404 ca → ngày quá khứ → nhân viên không hợp lệ → ca ngừng dùng → trùng / chồng giờ', async () => {
    const post5 = (body: object) => rejected('manager', 'post', '/api/shift-assignments', body);
    expectFail(await post5({ staffId: 99999, shiftDefinitionId: 999, workDate: day(2) }), 404, 'RESOURCE_NOT_FOUND', /nhân viên #99999/);
    expectFail(await post5({ staffId: 22, shiftDefinitionId: 999, workDate: day(-1) }), 404, 'RESOURCE_NOT_FOUND', /ca làm việc #999/);
    ruleFailure(await post5({ staffId: 22, shiftDefinitionId: 5, workDate: day(-1) }), 'BR-NS-02', /quá khứ/); // ngày trước ca ngừng dùng
    ruleFailure(await post5({ staffId: 3, shiftDefinitionId: 1, workDate: day(-1) }), 'BR-NS-02', /quá khứ/); // ngày trước nhân viên không hợp lệ
    ruleFailure(await post5({ staffId: 3, shiftDefinitionId: 5, workDate: day(2) }), 'BR-NS-02', /Chỉ phân ca cho lễ tân/); // nhân viên trước ca ngừng dùng
  });

  it('404: nhân viên không có / ở chi nhánh khác / không phải nhân viên, ca không có / của chi nhánh khác; 404 đến trước các guard BR', async () => {
    for (const staffId of [99999, 9, 7, 1, 2]) {
      expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId, shiftDefinitionId: 1, workDate: day(1) }), 404, 'RESOURCE_NOT_FOUND', new RegExp(`nhân viên #${staffId}`));
    }
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 6, workDate: day(1) }), 404, 'RESOURCE_NOT_FOUND', /ca làm việc #6/);
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 999, workDate: day(1) }), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 999, workDate: day(-5) }), 404, 'RESOURCE_NOT_FOUND');
    // Quản lý chi nhánh 2 không có nhân viên nào ở chi nhánh mình để phân.
    expectFail(await rejected('manager2', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 6, workDate: day(1) }), 404, 'RESOURCE_NOT_FOUND');
  });

  it('thiếu trường → VALIDATION_FAILED; ngày không có thật / sai kiểu → MALFORMED_REQUEST (trước vai trò)', async () => {
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', {}), 400, 'VALIDATION_FAILED', 'staffId: Vui lòng chọn nhân viên; shiftDefinitionId: Vui lòng chọn ca làm việc; workDate: Vui lòng chọn ngày làm');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: '2026-02-30' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: '22', shiftDefinitionId: 1, workDate: day(1) }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22.5, shiftDefinitionId: 1, workDate: day(1) }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'post', '/api/shift-assignments', { staffId: 22 }), 400, 'VALIDATION_FAILED'); // trước vai trò
    expectFail(await rejected('desk', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(2) }), 403, 'ACCESS_DENIED');
  });
});

// ─── Hủy phân ca (#6) và vắng đột xuất (#7) ───

describe('hủy phân ca (#6, BR-NS-02)', () => {
  const cancel = (who: Who, id: number | string, body: unknown = { reason: 'Chi nhánh đổi lịch' }) => send(who, 'post', `/api/shift-assignments/${id}/cancel`, body);

  it('SCHEDULED → CANCELLED kèm lý do; ca hôm nay chưa bắt đầu hủy được; thông báo cho nhân viên', async () => {
    const chieuToday = await assignmentOf(5, day(0)); // Ca chiều 13:00, bây giờ 09:00
    expect(chieuToday.shiftName).toBe('Ca chiều');
    const res = await cancel('manager', chieuToday.shiftAssignmentId, { reason: '  Ca ngắn người  ' });
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ shiftAssignmentId: chieuToday.shiftAssignmentId, status: 'CANCELLED', reason: 'Ca ngắn người' });
    const got = (await notices('doctor')).filter((n) => n.type === 'SHIFT_ASSIGNMENT_CANCELLED');
    expect(got).toHaveLength(1);
    expect(got[0]).toMatchObject({ title: `Ca làm việc ngày 23/10/2026 đã bị hủy`, readAt: null, linkUrl: null });
    expect(got[0].body).toBe('Ca Ca chiều ngày 23/10/2026 (13:00–17:30) tại PetCare Cầu Giấy của bạn đã bị hủy. Lý do: Ca ngắn người.');
  });

  it('yêu cầu PENDING của phân ca tự bị từ chối bởi hệ thống (Yêu cầu#5): decidedBy rỗng, có lý do, có closedAt', async () => {
    const target = await assignmentOf(5, day(3));
    expect(target.pendingRequestId).toBe(1);
    await cancel('manager', target.shiftAssignmentId);
    expect((await assignments({ staffId: 5 })).find((a) => a.shiftAssignmentId === target.shiftAssignmentId)).toMatchObject({ status: 'CANCELLED', pendingRequestId: null });
    const [rejectedRequest] = (await requestsOf('doctor', { status: 'REJECTED' })).filter((r) => r.shiftRequestId === 1);
    expect(rejectedRequest).toMatchObject({ status: 'REJECTED', decidedBy: null, decisionNote: 'Phân ca đã bị quản lý hủy' });
    expect(rejectedRequest.closedAt).toEqual(expect.any(String));
    expect(await pendingList()).toHaveLength(1);
  });

  it('ca đã bắt đầu còn SCHEDULED → 400 BR-NS-02 (dùng ghi vắng đột xuất); phân ca đã qua mà không còn SCHEDULED → 409 TRƯỚC (A11, đo BE)', async () => {
    const started = await assignmentOf(4, day(0)); // Ca sáng 07:30, bây giờ 09:00
    ruleFailure(await failure(cancel('manager', started.shiftAssignmentId)), 'BR-NS-02', 'Ca đã bắt đầu, không hủy được; hãy ghi nhận vắng đột xuất (BR-NS-02)');
    const absent = await assignmentOf(4, day(-2));
    expectFail(await failure(cancel('manager', absent.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái phân ca từ ABSENT sang CANCELLED');
  });

  it('không còn SCHEDULED → 409 INVALID_STATE_TRANSITION (hủy lại, đã ON_LEAVE)', async () => {
    const target = await assignmentOf(5, day(2));
    await cancel('manager', target.shiftAssignmentId);
    expectFail(await failure(cancel('manager', target.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái phân ca từ CANCELLED sang CANCELLED');
    const onLeave = await assignmentOf(4, day(5));
    expectFail(await failure(cancel('manager', onLeave.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', /ON_LEAVE/);
  });

  it('thiếu / trống / quá 300 ký tự → VALIDATION_FAILED (sau vai trò); 404; 403 phạm vi; id sai kiểu → MALFORMED_REQUEST', async () => {
    const target = await assignmentOf(5, day(2));
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED', 'reason: Vui lòng nhập lý do');
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: '  ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: 'x'.repeat(301) })), 400, 'VALIDATION_FAILED', 'reason: Lý do tối đa 300 ký tự');
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: 5 })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(cancel('manager', 99999)), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy phân ca #99999');
    expectFail(await failure(cancel('manager2', target.shiftAssignmentId)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(cancel('manager2', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED');
    expectFail(await failure(cancel('manager', 'abc')), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(cancel('desk', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED'); // trước vai trò
    expectFail(await failure(cancel('desk', target.shiftAssignmentId)), 403, 'ACCESS_DENIED');
  });
});

describe('ghi nhận vắng đột xuất (#7, BR-NS-07)', () => {
  const absent = (who: Who, id: number | string, body: unknown = { reason: 'Không đến, không báo' }) => send(who, 'post', `/api/shift-assignments/${id}/absent`, body);

  it('SCHEDULED → ABSENT cho ca hôm nay (đã bắt đầu) và ca các ngày trước; thông báo nhân viên', async () => {
    const today = await assignmentOf(4, day(0));
    const res = await absent('manager', today.shiftAssignmentId, { reason: ' Không liên lạc được ' });
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ status: 'ABSENT', reason: 'Không liên lạc được' });
    const got = (await notices('desk')).filter((n) => n.type === 'SHIFT_ABSENCE_RECORDED');
    expect(got).toHaveLength(1);
    expect(got[0].title).toBe('Bạn được ghi nhận vắng mặt ngày 23/10/2026');
    expect(got[0].body).toBe('Quản lý đã ghi nhận bạn vắng mặt ca Ca sáng ngày 23/10/2026 tại PetCare Cầu Giấy. Lý do: Không liên lạc được.');
    // Ca chưa bắt đầu của hôm nay cũng ghi vắng được (tính theo ngày).
    expect((await absent('manager', (await assignmentOf(5, day(0))).shiftAssignmentId)).status).toBe(200);
  });

  it('ngày tương lai → 400 BR-NS-07; quá 7 ngày → 400 BR-NS-07; đúng 7 ngày thì được', async () => {
    const future = await assignmentOf(5, day(2));
    ruleFailure(await failure(absent('manager', future.shiftAssignmentId)), 'BR-NS-07', 'Ca của ngày tương lai chưa ghi nhận vắng được; hãy hủy phân ca hoặc để nhân viên xin nghỉ (BR-NS-07)');
    const tooOld = await assignmentOf(21, day(-9));
    ruleFailure(await failure(absent('manager', tooOld.shiftAssignmentId)), 'BR-NS-07', 'Chỉ ghi nhận vắng cho ca trong vòng 7 ngày gần đây (BR-NS-07)');
    const sevenDays = await assignmentOf(5, day(-7));
    expect((await absent('manager', sevenDays.shiftAssignmentId)).data.data.status).toBe('ABSENT');
  });

  it('ca đã ABSENT / CANCELLED ghi lại → 409, đến trước guard ngày (A11)', async () => {
    // Phân ca hôm nay không có yêu cầu mẫu; tạo yêu cầu cho ca ngày mai rồi để nó qua ngày không thực tế, nên dùng ca T−1 của nhân viên 5 (không có yêu cầu) làm ca 409.
    const yesterday = await assignmentOf(5, day(-1));
    await absent('manager', yesterday.shiftAssignmentId);
    expectFail(await failure(absent('manager', yesterday.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái phân ca từ ABSENT sang ABSENT');
    const already = await assignmentOf(4, day(-2));
    expectFail(await failure(absent('manager', already.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION');
    // Ca tương lai đã hủy: 409 đến TRƯỚC guard ngày (A11, đo BE).
    const cancelled = await assignmentOf(4, day(8));
    expectFail(await failure(absent('manager', cancelled.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái phân ca từ CANCELLED sang ABSENT');
  });

  it('lý do bắt buộc (VALIDATION_FAILED), 404, 403 phạm vi, vai trò', async () => {
    const target = await assignmentOf(5, day(-1));
    expectFail(await failure(absent('manager', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED', 'reason: Vui lòng nhập lý do');
    expectFail(await failure(absent('doctor', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED'); // trước vai trò
    expectFail(await failure(absent('manager', target.shiftAssignmentId, { reason: 'y'.repeat(301) })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(absent('manager', 99999)), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(absent('manager2', target.shiftAssignmentId)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(absent('doctor', target.shiftAssignmentId)), 403, 'ACCESS_DENIED');
  });
});

// ─── Lịch của tôi (#8) ───

describe('lịch làm việc của tôi (#8, BR-NS-03)', () => {
  it('chỉ phân ca của chính mình, kể cả đã nghỉ / vắng / hủy; sắp ngày rồi giờ; không phân trang', async () => {
    const rows = await mine('desk');
    expect(rows.every((a) => a.staffId === 4 && a.staffName === 'Lan Chi')).toBe(true);
    expect(rows.map((a) => a.status).filter((s) => s !== 'SCHEDULED').sort()).toEqual(['ABSENT', 'CANCELLED', 'ON_LEAVE']);
    expect(rows.map((a) => a.workDate)).toEqual([...rows.map((a) => a.workDate)].sort());
    expect(rows.find((a) => a.status === 'ABSENT')?.reason).toBe('Nghỉ ốm không báo trước.');
    expect(Array.isArray(rows)).toBe(true);
    expect((await mine('doctor')).every((a) => a.staffId === 5)).toBe(true);
    expect(await mine('doctor3')).toEqual([]);
    expect((await mine('doctor', day(0), day(0))).map((a) => a.workDate)).toEqual([day(0)]);
    expect((await mine('doctor')).find((a) => a.workDate === day(3))?.pendingRequestId).toBe(1);
  });

  it('from / to thiếu hoặc sai kiểu → MALFORMED_REQUEST; to < from, quá 62 ngày → 400 BR-NS-03; vai trò khác A06–A08 (kể cả quản lý) → 403', async () => {
    expectFail(await rejected('desk', 'get', '/api/me/shift-assignments', undefined, { from: day(0) }), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'to'");
    expectFail(await rejected('desk', 'get', '/api/me/shift-assignments', undefined, { from: 'hôm nay', to: day(1) }), 400, 'MALFORMED_REQUEST');
    ruleFailure(await rejected('desk', 'get', '/api/me/shift-assignments', undefined, { from: day(2), to: day(1) }), 'BR-NS-03');
    ruleFailure(await rejected('desk', 'get', '/api/me/shift-assignments', undefined, { from: day(0), to: day(62) }), 'BR-NS-03');
    expect((await mine('desk', day(0), day(61))).length).toBeGreaterThan(0);
    for (const who of ['manager', 'chain', 'admin', 'customer'] as const) {
      expectFail(await rejected(who, 'get', '/api/me/shift-assignments', undefined, { from: day(0), to: day(1) }), 403, 'ACCESS_DENIED');
    }
  });
});

// ─── Yêu cầu nghỉ / đổi ca (#9–#14) ───

describe('gửi yêu cầu nghỉ / đổi ca (#9, BR-NS-04)', () => {
  const request = (who: Who, body: unknown) => send(who, 'post', '/api/me/shift-requests', body);
  /** Phân ca SCHEDULED của chính người gọi, từ T+2 trở đi, chưa có yêu cầu. */
  const eligible = async (who: Who) => (await mine(who, day(2), day(11))).filter((a) => a.status === 'SCHEDULED' && a.pendingRequestId === null);

  it('LEAVE: 201 PENDING, phân ca hiện pendingRequestId, quản lý chi nhánh nhận thông báo SHIFT_REQUEST_SUBMITTED', async () => {
    const [a] = await eligible('desk');
    const res = await request('desk', { shiftAssignmentId: a.shiftAssignmentId, type: 'LEAVE', reason: '  Đi khám sức khỏe ' });
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({
      shiftAssignmentId: a.shiftAssignmentId, requesterId: 4, requesterName: 'Lan Chi', workDate: a.workDate, currentShiftName: a.shiftName, type: 'LEAVE',
      targetShiftDefinitionId: null, targetShiftName: null, reason: 'Đi khám sức khỏe', status: 'PENDING', decidedBy: null, decisionNote: null, closedAt: null,
    });
    expect((await mine('desk', a.workDate, a.workDate))[0].pendingRequestId).toBe(res.data.data.shiftRequestId);
    const got = (await notices('manager')).filter((n) => n.type === 'SHIFT_REQUEST_SUBMITTED');
    expect(got).toHaveLength(1);
    expect(got[0].title).toBe('Yêu cầu nghỉ ca làm việc mới');
    expect(got[0].body).toBe(`Lan Chi gửi yêu cầu nghỉ ca ${a.shiftName} ngày ${a.workDate.split('-').reverse().join('/')} (PetCare Cầu Giấy). Lý do: Đi khám sức khỏe. Vui lòng duyệt hoặc từ chối.`);
    expect((await notices('manager2')).filter((n) => n.type === 'SHIFT_REQUEST_SUBMITTED')).toHaveLength(0);
  });

  it('SWAP sang ca khác của chi nhánh trong cùng ngày: 201 kèm tên ca đích; thông báo "đổi ca"', async () => {
    const a = await assign(22, 1, day(3));
    const res = await request('doctor3', { shiftAssignmentId: a.shiftAssignmentId, type: 'SWAP', targetShiftDefinitionId: 2, reason: 'Sáng bận việc riêng' });
    expect(res.status).toBe(201);
    expect(res.data.data).toMatchObject({ type: 'SWAP', targetShiftDefinitionId: 2, targetShiftName: 'Ca chiều', currentShiftName: 'Ca sáng' });
    expect((await notices('manager')).find((n) => n.type === 'SHIFT_REQUEST_SUBMITTED')?.title).toBe('Yêu cầu đổi ca làm việc mới');
  });

  it('phải gửi trước giờ bắt đầu ca ít nhất 24 giờ: đúng 24 giờ được, chậm 1 phút thì không; ca đã bắt đầu cũng không', async () => {
    const onTime = await newShift('Ca đúng hạn', '09:00', '10:00');
    const late = await newShift('Ca thiếu một phút', '08:59', '10:00');
    const a1 = await assign(22, onTime.shiftDefinitionId, day(1));
    const a2 = await assign(23, late.shiftDefinitionId, day(1));
    expect((await request('doctor3', { shiftAssignmentId: a1.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' })).status).toBe(201);
    ruleFailure(await failure(request('doctor4', { shiftAssignmentId: a2.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' })), 'BR-NS-04', /ít nhất 24 giờ/);
    const today = await assignmentOf(5, day(0)); // Ca chiều 13:00 hôm nay, còn 4 giờ
    ruleFailure(await failure(request('doctor', { shiftAssignmentId: today.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' })), 'BR-NS-04', /ít nhất 24 giờ/);
    const started = await assignmentOf(4, day(0));
    ruleFailure(await failure(request('desk', { shiftAssignmentId: started.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' })), 'BR-NS-04');
  });

  it('phân ca không SCHEDULED, đã có yêu cầu chờ → 400 BR-NS-04', async () => {
    const onLeave = await assignmentOf(4, day(5));
    ruleFailure(await failure(request('desk', { shiftAssignmentId: onLeave.shiftAssignmentId, type: 'LEAVE', reason: 'Nghỉ nữa' })), 'BR-NS-04', 'Chỉ phân ca đang chờ làm mới được gửi yêu cầu (BR-NS-04)');
    const pending = await assignmentOf(5, day(3));
    ruleFailure(await failure(request('doctor', { shiftAssignmentId: pending.shiftAssignmentId, type: 'LEAVE', reason: 'Nghỉ nữa' })), 'BR-NS-04', 'Phân ca này đã có yêu cầu đang chờ xử lý (BR-NS-04)');
    // Yêu cầu cũ đã rút / đã đóng thì gửi lại được.
    const withdrawn = await assignmentOf(5, day(7));
    expect((await request('doctor', { shiftAssignmentId: withdrawn.shiftAssignmentId, type: 'LEAVE', reason: 'Nghỉ luôn' })).status).toBe(201);
  });

  it('SWAP thiếu ca đích, LEAVE thừa ca đích, ca đích ngừng dùng / trùng ca hiện tại / chồng giờ phân ca khác → 400 BR-NS-04; ca đích không có / chi nhánh khác → 404', async () => {
    const sang = await assign(22, 1, day(4)); // 07:30–12:00
    await assign(22, 2, day(4)); // 13:00–17:30
    const base = { shiftAssignmentId: sang.shiftAssignmentId, reason: 'Đổi ca' };
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP' })), 'BR-NS-04', /phải chọn ca đích/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'LEAVE', targetShiftDefinitionId: 2 })), 'BR-NS-04', /không có ca đích/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 5 })), 'BR-NS-04', 'Ca đích đã ngừng dùng (BR-NS-04)');
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 1 })), 'BR-NS-04', /trùng với ca hiện tại/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 3 })), 'BR-NS-04', 'Ca đích chồng giờ với phân ca khác trong ngày: Ca chiều (13:00–17:30) (BR-NS-04)');
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 2 })), 'BR-NS-04', /Ca đích chồng giờ với phân ca khác trong ngày: /); // chính ca chiều đã phân trong ngày
    expect((await request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 4 })).status).toBe(201); // 17:30–21:00 chỉ chạm đầu mút ca chiều
    expectFail(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 6 })), 404, 'RESOURCE_NOT_FOUND', /ca làm việc #6/);
    expectFail(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 999 })), 404, 'RESOURCE_NOT_FOUND');
  });

  it('thứ tự kiểm (đo BE): 404 phân ca → hình dạng LEAVE / SWAP → 404 ca đích → phân ca không SCHEDULED → còn dưới 24 giờ → ca đích ngừng dùng', async () => {
    const soon = await assign(22, 1, day(1)); // 07:30 ngày mai: còn 22 giờ 30 phút
    const base = { shiftAssignmentId: soon.shiftAssignmentId, reason: 'Thử thứ tự' };
    ruleFailure(await failure(request('doctor3', { ...base, type: 'LEAVE', targetShiftDefinitionId: 2 })), 'BR-NS-04', /không có ca đích/); // hình dạng trước giờ
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP' })), 'BR-NS-04', /phải chọn ca đích/);
    expectFail(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 999 })), 404, 'RESOURCE_NOT_FOUND', /ca làm việc #999/); // 404 ca đích trước giờ
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 5 })), 'BR-NS-04', /ít nhất 24 giờ/); // giờ trước ca đích ngừng dùng
    const cancelled = await assignmentOf(4, day(8)); // CANCELLED
    ruleFailure(await failure(request('desk', { shiftAssignmentId: cancelled.shiftAssignmentId, type: 'SWAP', reason: 'x' })), 'BR-NS-04', /phải chọn ca đích/); // hình dạng trước trạng thái
    expectFail(await failure(request('doctor3', { shiftAssignmentId: (await assignmentOf(4, day(3))).shiftAssignmentId, type: 'SWAP', reason: 'x' })), 404, 'RESOURCE_NOT_FOUND', /phân ca #/); // người khác: 404 trước hình dạng
  });

  it('phân ca của người khác hoặc không có → 404 (không lộ sự tồn tại); thiếu trường → VALIDATION_FAILED; kiểu sai → MALFORMED_REQUEST', async () => {
    const other = await assignmentOf(6, day(5));
    expectFail(await failure(request('desk', { shiftAssignmentId: other.shiftAssignmentId, type: 'LEAVE', reason: 'x' })), 404, 'RESOURCE_NOT_FOUND', /^Không tìm thấy phân ca #\d+$/);
    expectFail(await failure(request('desk', { shiftAssignmentId: 99999, type: 'LEAVE', reason: 'x' })), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(request('desk', {})), 400, 'VALIDATION_FAILED', 'shiftAssignmentId: Vui lòng chọn phân ca; type: Vui lòng chọn loại yêu cầu; reason: Vui lòng nhập lý do');
    expectFail(await failure(request('desk', { shiftAssignmentId: other.shiftAssignmentId, type: 'LEAVE', reason: ' ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(request('desk', { shiftAssignmentId: other.shiftAssignmentId, type: 'LEAVE', reason: 'x'.repeat(301) })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(request('desk', { shiftAssignmentId: other.shiftAssignmentId, type: 'QUIT', reason: 'x' })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(request('desk', { shiftAssignmentId: 'a', type: 'LEAVE', reason: 'x' })), 400, 'MALFORMED_REQUEST');
    // Quản lý không tự xin nghỉ (Q3 TBD): 403.
    expectFail(await failure(request('manager', { shiftAssignmentId: other.shiftAssignmentId, type: 'LEAVE', reason: 'x' })), 403, 'ACCESS_DENIED');
  });
});

describe('yêu cầu của tôi và rút yêu cầu (#10, #11, BR-NS-05)', () => {
  it('chỉ yêu cầu của mình, mới nhất trước; lọc status; phân trang', async () => {
    const mineDoctor = await requestsOf('doctor');
    expect(mineDoctor.map((r) => r.shiftRequestId)).toEqual([1, 5]); // #1 gửi 26 giờ trước, #5 gửi 60 giờ trước
    expect(mineDoctor.every((r) => r.requesterId === 5 && r.requesterName === 'BS. Minh Anh')).toBe(true);
    expect((await requestsOf('doctor', { status: 'WITHDRAWN' })).map((r) => r.shiftRequestId)).toEqual([5]);
    expect((await requestsOf('doctor', { status: 'APPROVED' }))).toEqual([]);
    expect((await requestsOf('desk')).map((r) => [r.shiftRequestId, r.status])).toEqual([[6, 'REJECTED'], [3, 'APPROVED']]);
    expect(await requestsOf('doctor3')).toEqual([]);
    const page = await get<PageResponse<ShiftRequest>>('doctor', '/api/me/shift-requests', { size: 1, page: 1 });
    expect(page).toMatchObject({ page: 1, size: 1, totalElements: 2, totalPages: 2 });
    expect(page.content.map((r) => r.shiftRequestId)).toEqual([5]);
    expectFail(await rejected('doctor', 'get', '/api/me/shift-requests', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'get', '/api/me/shift-requests'), 403, 'ACCESS_DENIED');
  });

  it('rút: PENDING → WITHDRAWN, phân ca giữ nguyên và hết pendingRequestId; gửi lại được', async () => {
    const res = await send('doctor', 'post', '/api/me/shift-requests/1/withdraw');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ shiftRequestId: 1, status: 'WITHDRAWN', decidedBy: null, closedAt: expect.any(String) });
    expect((await mine('doctor')).find((a) => a.workDate === day(3))).toMatchObject({ status: 'SCHEDULED', pendingRequestId: null });
    expect(await pendingList()).toHaveLength(1);
    const again = await send('doctor', 'post', '/api/me/shift-requests', { shiftAssignmentId: (await assignmentOf(5, day(3))).shiftAssignmentId, type: 'LEAVE', reason: 'Gửi lại' });
    expect(again.status).toBe(201);
  });

  it('rút lần hai hoặc yêu cầu đã xử lý → 409; yêu cầu của người khác / không có → 404; id sai kiểu → MALFORMED_REQUEST; vai trò', async () => {
    await send('doctor', 'post', '/api/me/shift-requests/1/withdraw');
    expectFail(await rejected('doctor', 'post', '/api/me/shift-requests/1/withdraw'), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ WITHDRAWN sang WITHDRAWN');
    expectFail(await rejected('desk', 'post', '/api/me/shift-requests/3/withdraw'), 409, 'INVALID_STATE_TRANSITION', /APPROVED/);
    expectFail(await rejected('desk', 'post', '/api/me/shift-requests/1/withdraw'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('desk', 'post', '/api/me/shift-requests/999/withdraw'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('desk', 'post', '/api/me/shift-requests/abc/withdraw'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/me/shift-requests/1/withdraw'), 403, 'ACCESS_DENIED');
  });
});

describe('yêu cầu của chi nhánh, duyệt, từ chối (#12–#14, BR-NS-06)', () => {
  it('mặc định PENDING, cũ nhất trước; lọc status; chỉ chi nhánh của mình; phân trang', async () => {
    const pending = await pendingList();
    expect(pending.map((r) => [r.shiftRequestId, r.requesterName, r.type, r.status])).toEqual([
      [1, 'BS. Minh Anh', 'LEAVE', 'PENDING'],
      [2, 'Thu Hà', 'SWAP', 'PENDING'],
    ]);
    expect(pending[1]).toMatchObject({ targetShiftDefinitionId: 2, targetShiftName: 'Ca chiều', currentShiftName: 'Ca sáng', workDate: day(4) });
    expect((await pendingList({ status: 'REJECTED' })).map((r) => r.shiftRequestId)).toEqual([4, 6]); // #4 gửi 52 giờ trước, #6 30 giờ trước... sắp createdAt tăng dần
    expect((await pendingList({ status: 'APPROVED' })).map((r) => r.shiftRequestId)).toEqual([3]);
    expect((await pendingList({ status: 'WITHDRAWN' })).map((r) => r.shiftRequestId)).toEqual([5]);
    expect((await get<PageResponse<ShiftRequest>>('manager2', '/api/shift-requests')).content).toEqual([]);
    expect(await get<PageResponse<ShiftRequest>>('manager', '/api/shift-requests', { size: 1 })).toMatchObject({ size: 1, totalElements: 2, totalPages: 2 });
    expectFail(await rejected('manager', 'get', '/api/shift-requests', undefined, { status: 'DONE' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'get', '/api/shift-requests'), 403, 'ACCESS_DENIED');
  });

  it('duyệt LEAVE: yêu cầu APPROVED (decidedBy = quản lý), phân ca → ON_LEAVE, nhân viên được thông báo', async () => {
    const res = await send('manager', 'post', '/api/shift-requests/1/approve');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ shiftRequestId: 1, status: 'APPROVED', decidedBy: 3, closedAt: expect.any(String) });
    // Đo BE: duyệt LEAVE ghi lý do của yêu cầu vào phân ca.
    expect((await mine('doctor')).find((a) => a.workDate === day(3))).toMatchObject({ status: 'ON_LEAVE', pendingRequestId: null, reason: 'Đưa mẹ đi khám bệnh, xin nghỉ ca chiều.' });
    const got = (await notices('doctor')).filter((n) => n.type === 'SHIFT_REQUEST_APPROVED');
    expect(got).toHaveLength(1);
    expect(got[0].body).toBe(`Yêu cầu nghỉ ca Ca chiều ngày ${day(3).split('-').reverse().join('/')} tại PetCare Cầu Giấy của bạn đã được quản lý duyệt.`);
    expect(await pendingList()).toHaveLength(1);
  });

  it('duyệt SWAP: phân ca đổi sang ca đích và vẫn SCHEDULED; yêu cầu GIỮ tên ca lúc gửi (đo BE: V60 current_shift)', async () => {
    const res = await send('manager', 'post', '/api/shift-requests/2/approve');
    expect(res.data.data).toMatchObject({ status: 'APPROVED', decidedBy: 3, currentShiftName: 'Ca sáng', targetShiftName: 'Ca chiều' });
    expect((await mine('caretaker')).find((a) => a.workDate === day(4))).toMatchObject({ status: 'SCHEDULED', shiftDefinitionId: 2, shiftName: 'Ca chiều', startTime: '13:00', pendingRequestId: null });
  });

  it('yêu cầu SWAP vừa gửi chụp tên ca lúc gửi: duyệt xong vẫn hiện ca cũ ở currentShiftName (đo BE: V60)', async () => {
    const a = await assign(22, 1, day(5)); // Ca sáng
    const sent = await post<ShiftRequest>('doctor3', '/api/me/shift-requests', { shiftAssignmentId: a.shiftAssignmentId, type: 'SWAP', targetShiftDefinitionId: 2, reason: 'Đổi ca' });
    expect(sent.currentShiftName).toBe('Ca sáng');
    const res = await send('manager', 'post', `/api/shift-requests/${sent.shiftRequestId}/approve`);
    expect(res.data.data).toMatchObject({ currentShiftName: 'Ca sáng', targetShiftName: 'Ca chiều' });
    expect((await mine('doctor3', day(5), day(5)))[0].shiftName).toBe('Ca chiều');
  });

  it('phân ca đã bắt đầu → 400 BR-NS-06; ca đích ngừng dùng hoặc chồng giờ lúc duyệt → 400 BR-NS-06', async () => {
    // Đã bắt đầu: yêu cầu gửi đúng 24 giờ trước giờ bắt đầu, rồi đồng hồ chạy tới giờ ca.
    const early = await newShift('Ca sớm', '09:00', '10:00');
    const a = await assign(22, early.shiftDefinitionId, day(1));
    const req = await post<ShiftRequest>('doctor3', '/api/me/shift-requests', { shiftAssignmentId: a.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' });
    vi.setSystemTime(new Date(`${day(1)}T09:00:00+07:00`));
    ruleFailure(await rejected('manager', 'post', `/api/shift-requests/${req.shiftRequestId}/approve`), 'BR-NS-06', /đã bắt đầu/);
    vi.setSystemTime(new Date(`${day(1)}T08:59:59+07:00`));
    expect((await send('manager', 'post', `/api/shift-requests/${req.shiftRequestId}/approve`)).status).toBe(200);
  });

  it('SWAP: ca đích bị ngừng dùng sau khi gửi, hoặc đến lúc duyệt đã chồng phân ca mới → 400 BR-NS-06', async () => {
    const target = await newShift('Ca trưa', '11:00', '14:00');
    const sang = await assign(22, 1, day(5));
    const r1 = await post<ShiftRequest>('doctor3', '/api/me/shift-requests', { shiftAssignmentId: sang.shiftAssignmentId, type: 'SWAP', targetShiftDefinitionId: target.shiftDefinitionId, reason: 'Muốn ngủ nướng' });
    await assign(22, 2, day(5)); // 13:00–17:30, chồng ca trưa 11:00–14:00
    ruleFailure(await rejected('manager', 'post', `/api/shift-requests/${r1.shiftRequestId}/approve`), 'BR-NS-06', /Ca đích chồng giờ với phân ca khác trong ngày: Ca chiều/);

    const tiny = await newShift('Ca nhỏ', '20:00', '21:00');
    const toi = await assign(23, 4, day(5));
    const r2 = await post<ShiftRequest>('doctor4', '/api/me/shift-requests', { shiftAssignmentId: toi.shiftAssignmentId, type: 'SWAP', targetShiftDefinitionId: tiny.shiftDefinitionId, reason: 'Đổi' });
    await send('manager', 'patch', `/api/shift-definitions/${tiny.shiftDefinitionId}`, { active: false });
    ruleFailure(await rejected('manager', 'post', `/api/shift-requests/${r2.shiftRequestId}/approve`), 'BR-NS-06', /ngừng dùng/);
  });

  it('đã xử lý mà phân ca đã bắt đầu → 409 TRƯỚC guard 400 BR-NS-06 (A11, đo BE)', async () => {
    const early = await newShift('Ca sớm hai', '09:00', '10:00');
    const a = await assign(22, early.shiftDefinitionId, day(1));
    const req = await post<ShiftRequest>('doctor3', '/api/me/shift-requests', { shiftAssignmentId: a.shiftAssignmentId, type: 'LEAVE', reason: 'Việc nhà' });
    expect((await send('manager', 'post', `/api/shift-requests/${req.shiftRequestId}/approve`)).status).toBe(200);
    vi.setSystemTime(new Date(`${day(1)}T09:30:00+07:00`));
    expectFail(await rejected('manager', 'post', `/api/shift-requests/${req.shiftRequestId}/approve`), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ APPROVED sang APPROVED');
  });

  it('đã xử lý → 409 INVALID_STATE_TRANSITION (duyệt lại, duyệt sau khi từ chối, từ chối sau khi duyệt); 404; 403 phạm vi; id sai kiểu', async () => {
    await send('manager', 'post', '/api/shift-requests/1/approve');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/1/approve'), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ APPROVED sang APPROVED');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/1/reject', { decisionNote: 'Muộn rồi' }), 409, 'INVALID_STATE_TRANSITION', /APPROVED sang REJECTED/);
    expectFail(await rejected('manager', 'post', '/api/shift-requests/5/approve'), 409, 'INVALID_STATE_TRANSITION', /WITHDRAWN/);
    expectFail(await rejected('manager', 'post', '/api/shift-requests/999/approve'), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await rejected('manager2', 'post', '/api/shift-requests/2/approve'), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await rejected('manager2', 'post', '/api/shift-requests/2/reject', { decisionNote: 'x' }), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/abc/approve'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'post', '/api/shift-requests/2/approve'), 403, 'ACCESS_DENIED');
  });

  it('từ chối: REJECTED kèm decisionNote, phân ca giữ nguyên, nhân viên được thông báo; thiếu lý do → VALIDATION_FAILED', async () => {
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', {}), 400, 'VALIDATION_FAILED', 'decisionNote: Vui lòng nhập lý do từ chối');
    expectFail(await rejected('desk', 'post', '/api/shift-requests/2/reject', {}), 400, 'VALIDATION_FAILED'); // trước vai trò
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', { decisionNote: '  ' }), 400, 'VALIDATION_FAILED');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', { decisionNote: 'z'.repeat(301) }), 400, 'VALIDATION_FAILED', 'decisionNote: Lý do từ chối tối đa 300 ký tự');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager2', 'post', '/api/shift-requests/2/reject', {}), 400, 'VALIDATION_FAILED');
    const res = await send('manager', 'post', '/api/shift-requests/2/reject', { decisionNote: ' Ca chiều đủ người ' });
    expect(res.data.data).toMatchObject({ status: 'REJECTED', decidedBy: 3, decisionNote: 'Ca chiều đủ người', closedAt: expect.any(String) });
    expect((await mine('caretaker')).find((a) => a.workDate === day(4))).toMatchObject({ status: 'SCHEDULED', shiftName: 'Ca sáng', pendingRequestId: null });
    const got = (await notices('caretaker')).filter((n) => n.type === 'SHIFT_REQUEST_REJECTED');
    expect(got).toHaveLength(1);
    expect(got[0].body).toBe(`Yêu cầu đổi ca Ca sáng sang ca Ca chiều ngày ${day(4).split('-').reverse().join('/')} tại PetCare Cầu Giấy của bạn không được duyệt. Lý do: Ca chiều đủ người.`);
  });
});

// ─── Đăng ký ca theo tuần (#15–17, BR-NS-11, BR-NS-12) ───
// Tuần thứ Hai 09/11/2026 (T+17) trống với mọi nhân viên (dữ liệu mẫu chỉ tới T+10 = 02/11); tuần thứ Hai 26/10 (T+3) đã có phân ca QUẢN LÝ (MANAGER) của nhân viên 4, 5, 6, 21;
// tuần thứ Hai 19/10 (chứa hôm nay) đã quá hạn. Định mức 10 ca ([CFG] `workforce.weekly_required_shifts`) mock cố định.

const WEEK = '2026-11-09';
const WEEK_END = '2026-11-15';
const OVERDUE_WEEK = '2026-10-19';
const MALFORMED_BODY = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu';
const INVALID = 'Đăng ký tuần không hợp lệ: ';
const OUTSIDE = 'nằm ngoài tuần 09/11/2026 – 15/11/2026';
const regUrl = (week: string) => `/api/me/shift-registrations/${week}`;
const item = (offset: number, shiftDefinitionId: number, week = WEEK) => ({ workDate: addDays(week, offset), shiftDefinitionId });
/** 10 mục: Ca sáng (#1) + Ca chiều (#2), thứ Hai đến thứ Sáu. */
const fullWeek = (week = WEEK) => [0, 1, 2, 3, 4].flatMap((n) => [item(n, 1, week), item(n, 2, week)]);
const registration = (who: Who, week = WEEK) => get<ShiftRegistration>(who, regUrl(week));
const register = (who: Who, items: unknown, week = WEEK) => send(who, 'put', regUrl(week), { items });
const saved = async (who: Who, items: unknown, week = WEEK) => (await register(who, items, week)).data.data as ShiftRegistration;
const refused = (who: Who, items: unknown, week = WEEK) => rejected(who, 'put', regUrl(week), { items });
const summaries = (week = WEEK, who: Who = 'manager') => get<ShiftWeeklySummary[]>(who, '/api/shift-weekly-summaries', { weekStart: week });
const idsOf = (list: ShiftAssignment[]) => list.map((a) => a.shiftAssignmentId);

describe('đăng ký của tôi trong tuần (#17)', () => {
  it('tuần chưa có ca: định mức 10, 0 ca, hạn là 00:00 thứ Hai giờ Việt Nam dạng UTC không phần nghìn giây', async () => {
    const res = await send('desk', 'get', regUrl(WEEK));
    expect(res.data).toMatchObject({ message: 'Đăng ký ca của tôi trong tuần', code: 200 });
    expect(res.data.data).toEqual({ weekStart: WEEK, requiredShifts: 10, shiftCount: 0, editableUntil: '2026-11-08T17:00:00Z', assignments: [] });
  });

  it('tuần đã có phân ca do quản lý xếp: chỉ phân ca SCHEDULED của chính mình (nghỉ / hủy không hiện), khớp lịch của tôi (#8), source MANAGER', async () => {
    const week = '2026-10-26';
    for (const who of ['desk', 'vet', 'caretaker', 'doctor2'] as const) {
      const reg = await registration(who, week);
      const all = await mine(who, week, addDays(week, 6));
      const scheduled = all.filter((a) => a.status === 'SCHEDULED');
      expect(scheduled.length, who).toBeGreaterThan(0);
      expect(idsOf(reg.assignments), who).toEqual(idsOf(scheduled));
      expect(reg.shiftCount, who).toBe(scheduled.length);
      expect(reg.assignments.every((a) => a.source === 'MANAGER' && a.status === 'SCHEDULED'), who).toBe(true);
    }
    // Nhân viên 4 có ca ON_LEAVE (T+5) và CANCELLED (T+8) trong tuần này: có ở #8, không có ở #17.
    expect((await mine('desk', week, addDays(week, 6))).filter((a) => a.status !== 'SCHEDULED').length).toBeGreaterThanOrEqual(2);
    expect((await registration('desk', week)).assignments.every((a) => a.staffId === 4)).toBe(true);
  });

  it('không kiểm hạn: xem được tuần đã quá hạn; năm 2000 và 2100 là biên hợp lệ', async () => {
    expect(await registration('desk', OVERDUE_WEEK)).toMatchObject({ requiredShifts: 10, editableUntil: '2026-10-18T17:00:00Z' });
    expect(await registration('desk', '2000-01-03')).toMatchObject({ shiftCount: 0, editableUntil: '2000-01-02T17:00:00Z' });
    expect(await registration('desk', '2100-12-27')).toMatchObject({ shiftCount: 0, editableUntil: '2100-12-26T17:00:00Z' });
  });

  it('BR-NS-11: không phải thứ Hai và năm ngoài 2000–2100 (thứ Hai được kiểm trước năm)', async () => {
    ruleFailure(await rejected('desk', 'get', regUrl('2026-11-10')), 'BR-NS-11', 'Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)');
    ruleFailure(await rejected('desk', 'get', regUrl('1999-12-27')), 'BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)');
    ruleFailure(await rejected('desk', 'get', regUrl('2101-01-03')), 'BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)');
    ruleFailure(await rejected('desk', 'get', regUrl('2101-01-04')), 'BR-NS-11', 'Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)');
  });

  it('weekStart sai kiểu → 400 MALFORMED_REQUEST, đến TRƯỚC vai trò; quản lý và vai khác → 403', async () => {
    for (const bad of ['abc', '2026-02-30', '2026-11-09T00:00', '2026-W46']) {
      expectFail(await rejected('desk', 'get', regUrl(bad)), 400, 'MALFORMED_REQUEST', "Tham số 'weekStart' sai kiểu dữ liệu");
    }
    expectFail(await rejected('manager', 'get', regUrl('abc')), 400, 'MALFORMED_REQUEST');
    for (const who of ['manager', 'chain', 'admin', 'customer'] as const) expectFail(await rejected(who, 'get', regUrl(WEEK)), 403, 'ACCESS_DENIED');
  });
});

describe('đăng ký ca theo tuần (#15, BR-NS-11, BR-NS-12)', () => {
  it('lưu 10 mục: 200, phân ca SELF SCHEDULED của chính mình, sắp theo ngày rồi giờ; hiện ở #17, lịch của tôi và lịch chi nhánh; vượt định mức vẫn được', async () => {
    const res = await register('desk', fullWeek());
    expect(res.status).toBe(200);
    expect(res.data).toMatchObject({ message: 'Đã lưu đăng ký ca của tuần', code: 200 });
    const reg = res.data.data as ShiftRegistration;
    expect(Object.keys(reg).sort()).toEqual(['assignments', 'editableUntil', 'requiredShifts', 'shiftCount', 'weekStart']);
    expect(reg).toMatchObject({ weekStart: WEEK, requiredShifts: 10, shiftCount: 10, editableUntil: '2026-11-08T17:00:00Z' });
    expect(reg.assignments.map((a) => [a.workDate, a.shiftName])).toEqual([0, 1, 2, 3, 4].flatMap((n) => [[addDays(WEEK, n), 'Ca sáng'], [addDays(WEEK, n), 'Ca chiều']]));
    for (const a of reg.assignments) expect(a).toMatchObject({ source: 'SELF', status: 'SCHEDULED', staffId: 4, branchId: 1, reason: null, pendingRequestId: null });
    expect(await registration('desk')).toEqual(reg);
    expect(idsOf(await mine('desk', WEEK, WEEK_END))).toEqual(idsOf(reg.assignments));
    expect((await assignments({ staffId: 4, from: WEEK, to: WEEK_END })).every((a) => a.source === 'SELF')).toBe(true);
    expect((await saved('vet', [...fullWeek(), item(5, 1)])).shiftCount).toBe(11);
  });

  it('idempotent: gửi lại cùng nội dung hoặc đổi thứ tự mục không đổi gì (id giữ nguyên), kể cả trường thừa trong body', async () => {
    const first = await saved('desk', fullWeek());
    expect(await saved('desk', fullWeek())).toEqual(first);
    expect((await saved('desk', [...fullWeek()].reverse())).assignments).toEqual(first.assignments);
    expect((await send('desk', 'put', regUrl(WEEK), { items: fullWeek(), ghiChu: 'bỏ qua' })).data.data).toEqual(first);
    expect(await mine('desk', WEEK, WEEK_END)).toHaveLength(10);
  });

  it('sửa đăng ký: mục thiếu → CANCELLED (lý do hệ thống), mục mới → phân ca mới, mục giữ nguyên không đổi; yêu cầu PENDING của ca bị bỏ → REJECTED do hệ thống', async () => {
    const first = await saved('desk', fullWeek());
    const dropped = first.assignments[0]; // thứ Hai, Ca sáng
    const leave = await post<ShiftRequest>('desk', '/api/me/shift-requests', { shiftAssignmentId: dropped.shiftAssignmentId, type: 'LEAVE', reason: 'Xin nghỉ thử' });
    const after = await saved('desk', [...fullWeek().slice(1), item(5, 1)]); // bỏ thứ Hai Ca sáng, thêm thứ Bảy Ca sáng
    expect(after.shiftCount).toBe(10);
    const kept = idsOf(first.assignments.slice(1));
    expect(idsOf(after.assignments).filter((id) => kept.includes(id))).toEqual(kept);
    const added = after.assignments.find((a) => a.workDate === addDays(WEEK, 5))!;
    expect(added).toMatchObject({ source: 'SELF', status: 'SCHEDULED' });
    expect(added.shiftAssignmentId).toBeGreaterThan(Math.max(...idsOf(first.assignments)));
    const old = (await mine('desk', WEEK, WEEK_END)).find((a) => a.shiftAssignmentId === dropped.shiftAssignmentId)!;
    expect(old).toMatchObject({ status: 'CANCELLED', source: 'SELF', reason: 'Nhân viên sửa đăng ký tuần', pendingRequestId: null });
    expect((await requestsOf('desk')).find((r) => r.shiftRequestId === leave.shiftRequestId)).toMatchObject({
      status: 'REJECTED', decidedBy: null, decisionNote: 'Phân ca đã bị bỏ khi nhân viên sửa đăng ký tuần', closedAt: expect.any(String),
    });
  });

  it('thêm lại mục đã bỏ → phân ca mới (id mới), bản cũ vẫn CANCELLED', async () => {
    const first = await saved('desk', fullWeek());
    const dropped = first.assignments[9]; // thứ Sáu, Ca chiều
    await saved('desk', [...fullWeek().slice(0, 9), item(5, 1)]);
    const back = await saved('desk', fullWeek());
    const again = back.assignments.find((a) => a.workDate === dropped.workDate && a.shiftDefinitionId === dropped.shiftDefinitionId)!;
    expect(again.shiftAssignmentId).not.toBe(dropped.shiftAssignmentId);
    const cancelled = (await mine('desk', WEEK, WEEK_END)).filter((a) => a.status === 'CANCELLED');
    expect(idsOf(cancelled)).toContain(dropped.shiftAssignmentId);
    expect(cancelled).toHaveLength(2); // cũ thứ Sáu Ca chiều + thứ Bảy Ca sáng
  });

  it('không thông báo cho ai (BR-NS-08)', async () => {
    const counts = async () => [(await notices('desk')).length, (await notices('manager')).length];
    const before = await counts();
    await saved('desk', fullWeek());
    await saved('desk', [...fullWeek().slice(1), item(5, 1)]);
    expect(await counts()).toEqual(before);
  });

  it('phân ca do quản lý xếp không bị đụng nhưng được tính vào định mức và vào kiểm tra chồng giờ', async () => {
    const manager = await assign(4, 4, addDays(WEEK, 2)); // Ca tối thứ Tư, chạm Ca chiều ở 17:30 nên không chồng
    const nine = fullWeek().filter((x) => !(x.workDate === addDays(WEEK, 4) && x.shiftDefinitionId === 2));
    const reg = await saved('desk', nine);
    expect(reg.shiftCount).toBe(10);
    expect(reg.assignments.filter((a) => a.source === 'MANAGER').map((a) => a.shiftAssignmentId)).toEqual([manager.shiftAssignmentId]);
    expect(reg.assignments.filter((a) => a.source === 'SELF')).toHaveLength(9);
    // Thiếu: 8 mục tự đăng ký + 1 ca quản lý = 9; mọi thứ giữ nguyên sau lỗi.
    ruleFailure(await refused('desk', nine.slice(0, 8)), 'BR-NS-12', 'Tuần này có 9 ca (gồm 1 ca do quản lý xếp), cần tối thiểu 10 ca, còn thiếu 1 (BR-NS-12)');
    expect(await registration('desk')).toEqual(reg);
  });

  it('chồng giờ với phân ca do quản lý xếp → BR-NS-11 nêu ca của quản lý, mỗi mục chồng một dòng (thứ tự giữa nhiều ca của quản lý: CHƯA ĐO)', async () => {
    await assign(4, 3, addDays(WEEK, 2)); // Ca hành chính 08:00–17:00 thứ Tư: chồng Ca sáng và Ca chiều
    const clash = 'chồng giờ với phân ca do quản lý xếp ngày 11/11/2026: Ca hành chính (08:00–17:00)';
    ruleFailure(await refused('desk', fullWeek()), 'BR-NS-11', `${INVALID}Mục 5: ${clash}; Mục 6: ${clash} (BR-NS-11)`);
    expect((await registration('desk')).shiftCount).toBe(1);
  });

  it('BR-NS-12: chưa đủ định mức (kể cả items rỗng) → 400, không lưu gì; items rỗng không xóa đăng ký cũ', async () => {
    ruleFailure(await refused('desk', []), 'BR-NS-12', 'Tuần này có 0 ca (gồm 0 ca do quản lý xếp), cần tối thiểu 10 ca, còn thiếu 10 (BR-NS-12)');
    ruleFailure(await refused('desk', fullWeek().slice(0, 9)), 'BR-NS-12', 'Tuần này có 9 ca (gồm 0 ca do quản lý xếp), cần tối thiểu 10 ca, còn thiếu 1 (BR-NS-12)');
    expect((await registration('desk')).shiftCount).toBe(0);
    const first = await saved('desk', fullWeek());
    ruleFailure(await refused('desk', []), 'BR-NS-12', 'Tuần này có 0 ca (gồm 0 ca do quản lý xếp), cần tối thiểu 10 ca, còn thiếu 10 (BR-NS-12)');
    expect(await registration('desk')).toEqual(first);
  });

  it('quá hạn: tuần chứa hôm nay hoặc đã qua → 400 BR-NS-11 (ngày dd/MM/yyyy); đến SAU thứ Hai / năm và TRƯỚC lỗi theo mục / định mức', async () => {
    const overdue = (date: string) => `Đã quá hạn đăng ký tuần ${date} (hạn là 00:00 thứ Hai); chỉ còn xin nghỉ hoặc đổi ca (BR-NS-11)`;
    ruleFailure(await refused('desk', fullWeek(OVERDUE_WEEK), OVERDUE_WEEK), 'BR-NS-11', overdue('19/10/2026'));
    ruleFailure(await refused('desk', [], OVERDUE_WEEK), 'BR-NS-11', overdue('19/10/2026'));
    ruleFailure(await refused('desk', [item(0, 99999999, OVERDUE_WEEK)], OVERDUE_WEEK), 'BR-NS-11', overdue('19/10/2026'));
    ruleFailure(await refused('desk', [], '2026-10-12'), 'BR-NS-11', overdue('12/10/2026'));
    ruleFailure(await refused('desk', [], '2026-10-20'), 'BR-NS-11', 'Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)');
    ruleFailure(await refused('desk', [], '1999-12-27'), 'BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)');
    ruleFailure(await refused('desk', [], '2101-01-03'), 'BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)');
    // Tuần kế tiếp (26/10) còn mở tới 00:00 thứ Hai.
    expect((await register('doctor3', fullWeek('2026-10-26'), '2026-10-26')).status).toBe(200);
  });

  it('biên hạn: đúng 00:00 thứ Hai giờ Việt Nam đã quá hạn, trước đó 1 mili giây còn được', async () => {
    pinClock('2026-11-08T23:59:59.999+07:00');
    expect((await register('doctor3', fullWeek())).status).toBe(200);
    pinClock('2026-11-09T00:00:00+07:00');
    ruleFailure(await refused('doctor3', fullWeek()), 'BR-NS-11', 'Đã quá hạn đăng ký tuần 09/11/2026 (hạn là 00:00 thứ Hai); chỉ còn xin nghỉ hoặc đổi ca (BR-NS-11)');
  });

  it('lỗi theo mục gộp trong MỘT câu BR-NS-11, theo thứ tự mục; chồng giờ giữa các mục đến sau cùng; không lưu gì', async () => {
    const items = [item(0, 1), item(0, 1), item(7, 1), item(1, 5), item(2, 99999999), item(3, 1), item(3, 3)];
    ruleFailure(
      await refused('desk', items),
      'BR-NS-11',
      `${INVALID}Mục 2: trùng với mục 1 (cùng ngày, cùng ca); Mục 3: ngày 16/11/2026 ${OUTSIDE}; Mục 4: ca 'Ca đêm cũ' đã ngừng dùng; ` +
        `Mục 5: ca #99999999 không tồn tại hoặc không thuộc chi nhánh của bạn; Mục 6 và mục 7 chồng giờ ngày 12/11/2026: Ca sáng (07:30–12:00) / Ca hành chính (08:00–17:00) (BR-NS-11)`,
    );
    expect((await registration('desk')).shiftCount).toBe(0);
  });

  it('mục lệch tuần vừa ngoài tuần vừa lỗi ca thì báo cả hai; ca của chi nhánh khác coi như không tồn tại; mục lỗi không bị tính trùng hay chồng giờ', async () => {
    const outside = `ngày 16/11/2026 ${OUTSIDE}`;
    ruleFailure(
      await refused('desk', [item(7, 5), item(7, 5)]),
      'BR-NS-11',
      `${INVALID}Mục 1: ${outside}; Mục 1: ca 'Ca đêm cũ' đã ngừng dùng; Mục 2: ${outside}; Mục 2: ca 'Ca đêm cũ' đã ngừng dùng (BR-NS-11)`,
    );
    ruleFailure(await refused('desk', [item(0, 6)]), 'BR-NS-11', `${INVALID}Mục 1: ca #6 không tồn tại hoặc không thuộc chi nhánh của bạn (BR-NS-11)`);
    ruleFailure(await refused('desk', [item(-1, 1)]), 'BR-NS-11', `${INVALID}Mục 1: ngày 08/11/2026 ${OUTSIDE} (BR-NS-11)`);
    // Ca đêm cũ (21:00–23:00, ngừng dùng) chồng Ca khuya (20:00–23:30) nhưng mục ngừng dùng không dự phần vào chồng giờ.
    const late = await newShift('Ca khuya', '20:00', '23:30');
    ruleFailure(await refused('desk', [item(0, 5), item(0, late.shiftDefinitionId)]), 'BR-NS-11', `${INVALID}Mục 1: ca 'Ca đêm cũ' đã ngừng dùng (BR-NS-11)`);
  });

  it('chồng giờ nhiều cặp theo thứ tự (i, j) với ca của mục đứng trước nêu trước; chạm đầu mút và mục trùng không tính', async () => {
    const day0 = 'ngày 09/11/2026';
    ruleFailure(
      await refused('desk', [item(0, 3), item(0, 1), item(0, 2), item(0, 4)]),
      'BR-NS-11',
      `${INVALID}Mục 1 và mục 2 chồng giờ ${day0}: Ca hành chính (08:00–17:00) / Ca sáng (07:30–12:00); Mục 1 và mục 3 chồng giờ ${day0}: Ca hành chính (08:00–17:00) / Ca chiều (13:00–17:30) (BR-NS-11)`,
    );
    ruleFailure(
      await refused('desk', [item(0, 1), item(0, 3), item(0, 3)]),
      'BR-NS-11',
      `${INVALID}Mục 3: trùng với mục 2 (cùng ngày, cùng ca); Mục 1 và mục 2 chồng giờ ${day0}: Ca sáng (07:30–12:00) / Ca hành chính (08:00–17:00) (BR-NS-11)`,
    );
  });

  it('VALIDATION_FAILED: thiếu items, phần tử null, thiếu trường, quá 100 phần tử; đến TRƯỚC vai trò và TRƯỚC mọi kiểm BR-NS-11 (đo BE)', async () => {
    const send400 = (payload: unknown, week = WEEK, who: Who = 'desk') => rejected(who, 'put', regUrl(week), payload);
    expectFail(await send400({}), 400, 'VALIDATION_FAILED', 'items: Vui lòng gửi danh sách ca đăng ký');
    expectFail(await send400({ items: null }), 400, 'VALIDATION_FAILED', 'items: Vui lòng gửi danh sách ca đăng ký');
    expectFail(await send400({ items: [null] }), 400, 'VALIDATION_FAILED', 'items[0]: Mục đăng ký không được để trống');
    expectFail(await send400({ items: [{ workDate: WEEK }] }), 400, 'VALIDATION_FAILED', 'items[0].shiftDefinitionId: Vui lòng chọn ca làm việc');
    expectFail(await send400({ items: [{ shiftDefinitionId: 1 }] }), 400, 'VALIDATION_FAILED', 'items[0].workDate: Vui lòng chọn ngày làm');
    expectFail(await send400({ items: [{}] }), 400, 'VALIDATION_FAILED', 'items[0].shiftDefinitionId: Vui lòng chọn ca làm việc; items[0].workDate: Vui lòng chọn ngày làm');
    expectFail(await send400({ items: Array.from({ length: 101 }, () => item(0, 1)) }), 400, 'VALIDATION_FAILED', 'items: Tối đa 100 mục mỗi lần đăng ký');
    // Đúng 100 phần tử qua được kiểm định dạng rồi mới bị BR-NS-11 (trùng).
    expect((await send400({ items: Array.from({ length: 100 }, () => item(0, 1)) })).data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expectFail(await send400({}, WEEK, 'manager'), 400, 'VALIDATION_FAILED');
    expectFail(await send400({}, '2026-11-10'), 400, 'VALIDATION_FAILED');
    expectFail(await send400({}, OVERDUE_WEEK), 400, 'VALIDATION_FAILED');
  });

  it('MALFORMED_REQUEST: body vắng / không phải object, items sai kiểu, workDate không phải ngày thật, shiftDefinitionId không nguyên, weekStart sai kiểu; đến TRƯỚC kiểm định dạng và vai trò', async () => {
    const bodies: unknown[] = [
      undefined, [], 5, '"abc"', { items: 'x' }, { items: 5 }, { items: {} }, { items: ['x'] }, { items: [[]] },
      { items: [{ workDate: 'xx', shiftDefinitionId: 1 }] }, { items: [{ workDate: '2026-02-30', shiftDefinitionId: 1 }] },
      { items: [{ workDate: WEEK, shiftDefinitionId: 'a' }] }, { items: [{ workDate: WEEK, shiftDefinitionId: 1.5 }] }, { items: [null, { workDate: 5, shiftDefinitionId: 1 }] },
    ];
    for (const body of bodies) expectFail(await rejected('desk', 'put', regUrl(WEEK), body), 400, 'MALFORMED_REQUEST', MALFORMED_BODY);
    expectFail(await rejected('manager', 'put', regUrl(WEEK), { items: [{ workDate: 'xx', shiftDefinitionId: 1 }] }), 400, 'MALFORMED_REQUEST', MALFORMED_BODY);
    expectFail(await rejected('desk', 'put', regUrl('abc'), { items: fullWeek() }), 400, 'MALFORMED_REQUEST', "Tham số 'weekStart' sai kiểu dữ liệu");
    for (const who of ['manager', 'chain', 'admin', 'customer'] as const) expectFail(await refused(who, fullWeek()), 403, 'ACCESS_DENIED');
  });

  it('state cũ: phân ca chưa có `source` coi là MANAGER (không bị hủy khi sửa đăng ký, vẫn tính định mức)', async () => {
    const KEY = 'petcare-mock-workforce';
    const manager = await assign(4, 4, addDays(WEEK, 2));
    const state = JSON.parse(localStorage.getItem(KEY)!) as { assignments: { shiftAssignmentId: number; source?: string }[] };
    for (const a of state.assignments) delete a.source;
    localStorage.setItem(KEY, JSON.stringify(state));
    expect((await registration('desk')).assignments).toMatchObject([{ shiftAssignmentId: manager.shiftAssignmentId, source: 'MANAGER' }]);
    const reg = await saved('desk', fullWeek().slice(0, 9));
    expect(reg).toMatchObject({ shiftCount: 10 });
    expect(reg.assignments.filter((a) => a.source === 'MANAGER').map((a) => a.shiftAssignmentId)).toEqual([manager.shiftAssignmentId]);
  });
});

describe('thống kê số ca trong tuần (#16, BR-NS-12)', () => {
  it('mọi nhân viên A06–A08 ACTIVE của chi nhánh kể cả chưa có ca, sắp theo tên, đủ trường; quản lý và chi nhánh khác không có mặt', async () => {
    const rows = await summaries();
    expect(rows.map((r) => r.staffId)).toEqual(expect.arrayContaining([4, 5, 6, 21, 22, 23]));
    expect(rows.map((r) => r.staffId)).not.toContain(3);
    for (const r of rows) {
      expect(Object.keys(r).sort()).toEqual(['meetsRequirement', 'requiredShifts', 'role', 'shiftCount', 'staffId', 'staffName']);
      expect(['RECEPTIONIST', 'VET', 'CARETAKER']).toContain(r.role);
      expect(r).toMatchObject({ shiftCount: 0, requiredShifts: 10, meetsRequirement: false });
    }
    const names = rows.map((r) => r.staffName);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(await summaries(WEEK, 'manager2')).toEqual([]);
    expect((await send('manager', 'get', '/api/shift-weekly-summaries', undefined, { weekStart: WEEK })).data).toMatchObject({ message: 'Thống kê số ca trong tuần', code: 200 });
  });

  it('đếm phân ca SCHEDULED của tuần gồm cả SELF và MANAGER (nghỉ / hủy không tính); đạt khi số ca ≥ định mức', async () => {
    const week = '2026-10-26';
    const rows = await summaries(week);
    for (const [who, id] of [['desk', 4], ['vet', 5], ['caretaker', 6], ['doctor2', 21]] as const) {
      const expected = (await mine(who, week, addDays(week, 6))).filter((a) => a.status === 'SCHEDULED').length;
      expect(expected, who).toBeGreaterThan(0);
      expect(rows.find((r) => r.staffId === id)!.shiftCount, who).toBe(expected);
    }
    await saved('doctor3', fullWeek());
    expect((await summaries()).find((r) => r.staffId === 22)).toMatchObject({ shiftCount: 10, requiredShifts: 10, meetsRequirement: true });
    // Biên: 9 ca (toàn do quản lý xếp) chưa đạt, ca thứ 10 thì đạt.
    for (const n of [0, 1, 2, 3, 4, 5, 6]) await assign(23, 1, addDays(WEEK, n));
    await assign(23, 2, addDays(WEEK, 0));
    await assign(23, 2, addDays(WEEK, 1));
    expect((await summaries()).find((r) => r.staffId === 23)).toMatchObject({ shiftCount: 9, meetsRequirement: false });
    await assign(23, 2, addDays(WEEK, 2));
    expect((await summaries()).find((r) => r.staffId === 23)).toMatchObject({ shiftCount: 10, meetsRequirement: true });
  });

  it('nhân viên mới chưa có ca vẫn có mặt; bị khóa hoặc vô hiệu hóa thì biến mất, mở khóa thì trở lại', async () => {
    const created = await post<{ accountId: number }>('chain', '/api/staff', { email: 'vet.thongke@new.vn', phone: '0900000078', fullName: 'BS. Thống Kê', role: 'VET', branchId: 1 });
    const find = async () => (await summaries()).find((r) => r.staffId === created.accountId);
    expect(await find()).toMatchObject({ staffName: 'BS. Thống Kê', role: 'VET', shiftCount: 0, meetsRequirement: false });
    expect((await send('admin', 'post', `/api/accounts/${created.accountId}/lock`, { reason: 'Thử khóa' })).status).toBe(200);
    expect(await find()).toBeUndefined();
    expect((await send('admin', 'post', `/api/accounts/${created.accountId}/unlock`, { reason: 'Mở khóa' })).status).toBe(200);
    expect(await find()).toBeDefined();
    expect((await send('chain', 'post', `/api/staff/${created.accountId}/disable`)).status).toBe(200);
    expect(await find()).toBeUndefined();
  });

  it('không kiểm hạn: xem được tuần đã qua; biên năm 2000 / 2100', async () => {
    expect(Array.isArray(await summaries('2026-10-12'))).toBe(true);
    expect(Array.isArray(await summaries('2000-01-03'))).toBe(true);
    expect(Array.isArray(await summaries('2100-12-27'))).toBe(true);
  });

  it('weekStart thiếu / sai kiểu → 400 MALFORMED_REQUEST (trước vai trò); không phải thứ Hai / năm ngoài 2000–2100 → 400 BR-NS-11; vai khác 403', async () => {
    const url = '/api/shift-weekly-summaries';
    expectFail(await rejected('manager', 'get', url), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'weekStart'");
    expectFail(await rejected('desk', 'get', url), 400, 'MALFORMED_REQUEST', "Thiếu tham số 'weekStart'");
    for (const bad of ['abc', '2026-02-30']) expectFail(await rejected('manager', 'get', url, undefined, { weekStart: bad }), 400, 'MALFORMED_REQUEST', "Tham số 'weekStart' sai kiểu dữ liệu");
    ruleFailure(await rejected('manager', 'get', url, undefined, { weekStart: '2026-11-10' }), 'BR-NS-11', 'Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)');
    ruleFailure(await rejected('manager', 'get', url, undefined, { weekStart: '1999-12-27' }), 'BR-NS-11', 'Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)');
    for (const who of ['desk', 'vet', 'caretaker', 'chain', 'admin', 'customer'] as const) expectFail(await rejected(who, 'get', url, undefined, { weekStart: WEEK }), 403, 'ACCESS_DENIED');
  });
});

// ─── Phân quyền 7 vai trò × 17 endpoint, 401, tài khoản phải đổi mật khẩu ───

describe('phân quyền', () => {
  const ROLES = ['admin', 'chain', 'manager', 'desk', 'vet', 'caretaker', 'customer'] as const;
  const STAFF_ONLY: Who[] = ['desk', 'vet', 'caretaker'];
  /** Phân ca SCHEDULED từ T+2 của một nhân viên A06–A08 (vai khác dùng lễ tân), chưa có yêu cầu. */
  const freeAssignment = async (w: Who) =>
    (await mine(STAFF_ONLY.includes(w) ? w : 'desk', day(2), day(11))).find((a) => a.status === 'SCHEDULED' && a.pendingRequestId === null)!;
  const createLeave = async (w: Who) =>
    post<ShiftRequest>(w, '/api/me/shift-requests', { shiftAssignmentId: (await freeAssignment(w)).shiftAssignmentId, type: 'LEAVE', reason: 'Ma trận' });
  const BRANCH_MANAGER_ONLY: Who[] = ['manager'];

  /** Mỗi dòng: tên, vai được phép, hàm gọi hợp lệ cho vai đó (vai bị từ chối nhận cùng request nên vẫn phải ra 403, không phải lỗi khác). */
  const endpoints: { name: string; allowed: readonly Who[]; call: (who: Who) => Promise<AxiosResponse> }[] = [
    { name: '#1 GET /shift-definitions', allowed: ['manager', ...STAFF_ONLY], call: (w) => send(w, 'get', '/api/shift-definitions') },
    { name: '#2 POST /shift-definitions', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'post', '/api/shift-definitions', { name: 'Ca ma trận', startTime: '05:00', endTime: '06:00' }) },
    { name: '#3 PATCH /shift-definitions/{id}', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'patch', '/api/shift-definitions/4', { name: 'Ca tối mới' }) },
    { name: '#4 GET /shift-assignments', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'get', '/api/shift-assignments', undefined, { from: day(0), to: day(3) }) },
    { name: '#5 POST /shift-assignments', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(2) }) },
    { name: '#6 POST /shift-assignments/{id}/cancel', allowed: BRANCH_MANAGER_ONLY, call: async (w) => send(w, 'post', `/api/shift-assignments/${(await assignmentOf(5, day(2))).shiftAssignmentId}/cancel`, { reason: 'Ma trận' }) },
    { name: '#7 POST /shift-assignments/{id}/absent', allowed: BRANCH_MANAGER_ONLY, call: async (w) => send(w, 'post', `/api/shift-assignments/${(await assignmentOf(5, day(-1))).shiftAssignmentId}/absent`, { reason: 'Ma trận' }) },
    { name: '#8 GET /me/shift-assignments', allowed: STAFF_ONLY, call: (w) => send(w, 'get', '/api/me/shift-assignments', undefined, { from: day(0), to: day(3) }) },
    {
      name: '#9 POST /me/shift-requests',
      allowed: STAFF_ONLY,
      call: async (w) => send(w, 'post', '/api/me/shift-requests', { shiftAssignmentId: (await freeAssignment(w)).shiftAssignmentId, type: 'LEAVE', reason: 'Ma trận' }),
    },
    { name: '#10 GET /me/shift-requests', allowed: STAFF_ONLY, call: (w) => send(w, 'get', '/api/me/shift-requests') },
    {
      name: '#11 POST /me/shift-requests/{id}/withdraw',
      allowed: STAFF_ONLY,
      call: async (w) => {
        // Nhân viên 5 (vet) và 6 (caretaker) có sẵn yêu cầu PENDING (#1, #2); lễ tân 4 chưa có nên gửi mới rồi rút. Vai bị từ chối gọi thẳng #1.
        const id = w === 'caretaker' ? 2 : w === 'desk' ? (await createLeave('desk')).shiftRequestId : 1;
        return send(w, 'post', `/api/me/shift-requests/${id}/withdraw`);
      },
    },
    { name: '#12 GET /shift-requests', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'get', '/api/shift-requests') },
    { name: '#13 POST /shift-requests/{id}/approve', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'post', '/api/shift-requests/1/approve') },
    { name: '#14 POST /shift-requests/{id}/reject', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'post', '/api/shift-requests/2/reject', { decisionNote: 'Ma trận' }) },
    { name: '#15 PUT /me/shift-registrations/{weekStart}', allowed: STAFF_ONLY, call: (w) => send(w, 'put', regUrl(WEEK), { items: fullWeek() }) },
    { name: '#16 GET /shift-weekly-summaries', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'get', '/api/shift-weekly-summaries', undefined, { weekStart: WEEK }) },
    { name: '#17 GET /me/shift-registrations/{weekStart}', allowed: STAFF_ONLY, call: (w) => send(w, 'get', regUrl(WEEK)) },
  ];

  it('đủ 17 endpoint của hợp đồng', () => {
    expect(endpoints).toHaveLength(17);
  });

  for (const endpoint of endpoints) {
    it(`${endpoint.name}: chỉ ${endpoint.allowed.join(', ')} được; vai khác 403 ACCESS_DENIED`, async () => {
      for (const who of ROLES) {
        localStorage.clear(); // mỗi vai chạy trên dữ liệu mẫu nguyên vẹn để id lặp lại
        const status = await statusOf(endpoint.call(who));
        if (endpoint.allowed.includes(who)) expect(status, `${who} phải được`).toBeLessThan(300);
        else expect(status, `${who} phải bị từ chối`).toBe(403);
      }
    });
  }

  it('vai bị từ chối nhận đúng mã ACCESS_DENIED (không phải phạm vi) với envelope 6 trường', async () => {
    expectFail(await rejected('chain', 'get', '/api/shift-definitions'), 403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này');
    expectFail(await rejected('customer', 'get', '/api/me/shift-assignments', undefined, { from: day(0), to: day(1) }), 403, 'ACCESS_DENIED');
  });

  it('401 UNAUTHENTICATED cho mọi endpoint khi không có token', async () => {
    const calls: [string, string, unknown?][] = [
      ['get', '/api/shift-definitions'], ['post', '/api/shift-definitions', {}], ['patch', '/api/shift-definitions/1', {}],
      ['get', '/api/shift-assignments'], ['post', '/api/shift-assignments', {}], ['post', '/api/shift-assignments/1/cancel', {}], ['post', '/api/shift-assignments/1/absent', {}],
      ['get', '/api/me/shift-assignments'], ['post', '/api/me/shift-requests', {}], ['get', '/api/me/shift-requests'], ['post', '/api/me/shift-requests/1/withdraw'],
      ['get', '/api/shift-requests'], ['post', '/api/shift-requests/1/approve'], ['post', '/api/shift-requests/1/reject', {}],
      ['put', regUrl(WEEK), { items: [] }], ['get', '/api/shift-weekly-summaries'], ['get', regUrl(WEEK)],
    ];
    expect(calls).toHaveLength(17);
    for (const [method, url, body] of calls) {
      expectFail(await failure(client.request({ method, url, data: body })), 401, 'UNAUTHENTICATED');
    }
  });

  it('tài khoản còn phải đổi mật khẩu (newstaff) bị chặn 400 BR-TK-17 ở mọi endpoint nhân sự', async () => {
    ruleFailure(await rejected('newstaff', 'get', '/api/me/shift-assignments', undefined, { from: day(0), to: day(1) }), 'BR-TK-17');
  });
});

// ─── State, thông báo, BR-NS-09 ───

describe('state localStorage', () => {
  const KEY = 'petcare-mock-workforce';

  it('state rỗng {} hoặc hỏng → dùng lại dữ liệu mẫu, không nổ', async () => {
    localStorage.setItem(KEY, '{}');
    expect((await definitions()).length).toBe(5);
    expect((await pendingList()).length).toBe(2);
    localStorage.setItem(KEY, 'không phải json');
    expect((await assignments()).length).toBeGreaterThan(40);
    localStorage.setItem(KEY, 'null');
    expect((await definitions()).length).toBe(5);
  });

  it('state cũ thiếu bảng: bảng thiếu lấy dữ liệu mẫu, bảng có giữ nguyên; thao tác chính vẫn chạy', async () => {
    localStorage.setItem(KEY, JSON.stringify({ definitions: [{ shiftDefinitionId: 1, branchId: 1, name: 'Ca duy nhất', startTime: '06:00', endTime: '14:00', active: true }] }));
    expect((await definitions()).map((d) => d.name)).toEqual(['Ca duy nhất']);
    expect((await assignments()).length).toBeGreaterThan(40);
    expect((await pendingList()).length).toBe(2);
    const created = await post<ShiftDefinition>('manager', '/api/shift-definitions', { name: 'Ca thứ hai', startTime: '14:00', endTime: '22:00' });
    expect(created.shiftDefinitionId).toBe(2);
    // Đã ghi lại dạng đầy đủ.
    expect(Object.keys(JSON.parse(localStorage.getItem(KEY)!)).sort()).toEqual(['assignments', 'definitions', 'requests']);
  });

  it('tham chiếu mồ côi (phân ca trỏ ca không còn, yêu cầu trỏ phân ca không còn) không làm nổ danh sách', async () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        definitions: [],
        assignments: [{ shiftAssignmentId: 7, branchId: 1, staffId: 5, shiftDefinitionId: 42, workDate: day(2), status: 'SCHEDULED', reason: null, statusChangedBy: null, statusChangedAt: null }],
        requests: [
          { shiftRequestId: 1, shiftAssignmentId: 7, requesterId: 5, type: 'SWAP', targetShiftDefinitionId: 43, reason: 'x', status: 'PENDING', decidedBy: null, decisionNote: null, closedAt: null, createdAt: new Date().toISOString() },
          { shiftRequestId: 2, shiftAssignmentId: 99, requesterId: 5, type: 'LEAVE', targetShiftDefinitionId: null, reason: 'y', status: 'PENDING', decidedBy: null, decisionNote: null, closedAt: null, createdAt: new Date().toISOString() },
        ],
      }),
    );
    expect((await assignments())[0]).toMatchObject({ shiftName: 'Ca #42', startTime: '00:00', pendingRequestId: 1 });
    expect((await pendingList()).map((r) => r.shiftRequestId)).toEqual([1]); // yêu cầu #2 mồ côi không thuộc chi nhánh nào
    expect((await requestsOf('doctor')).map((r) => [r.shiftRequestId, r.workDate, r.currentShiftName, r.targetShiftName])).toEqual([[2, '', '', null], [1, day(2), '', 'Ca #43']]);
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/approve'), 404, 'RESOURCE_NOT_FOUND');
  });
});

describe('BR-NS-09: nhân viên rời chi nhánh (staffLeftBranch)', () => {
  it('phân ca SCHEDULED chưa bắt đầu → CANCELLED do hệ thống, yêu cầu PENDING → REJECTED; lịch sử và ca đã bắt đầu giữ nguyên; nhân viên được thông báo', async () => {
    const before = await assignments({ staffId: 5 });
    const startedToday = before.find((a) => a.workDate === day(0))!; // Ca chiều 13:00 chưa bắt đầu
    staffLeftBranch(5, 1);
    const after = await assignments({ staffId: 5 });
    for (const a of after) {
      const was = before.find((b) => b.shiftAssignmentId === a.shiftAssignmentId)!;
      if (was.status === 'SCHEDULED' && a.workDate >= day(0)) expect(a).toMatchObject({ status: 'CANCELLED', reason: 'Nhân viên không còn làm việc tại chi nhánh', pendingRequestId: null });
      else expect(a.status).toBe(was.status);
    }
    expect(after.find((a) => a.shiftAssignmentId === startedToday.shiftAssignmentId)?.status).toBe('CANCELLED');
    expect(after.find((a) => a.workDate === day(-1))?.status).toBe('SCHEDULED'); // đã qua: giữ nguyên
    const [r1] = (await requestsOf('doctor')).filter((r) => r.shiftRequestId === 1);
    expect(r1).toMatchObject({ status: 'REJECTED', decidedBy: null, decisionNote: 'Nhân viên không còn làm việc tại chi nhánh' });
    expect((await notices('doctor')).filter((n) => n.type === 'SHIFT_ASSIGNMENT_CANCELLED').length).toBeGreaterThan(5);
  });

  it('nhân viên đã bắt đầu ca hôm nay thì ca đó giữ nguyên; chi nhánh khác hoặc nhân viên không có lịch thì không làm gì', async () => {
    const started = await assignmentOf(4, day(0)); // Ca sáng 07:30 đã bắt đầu
    staffLeftBranch(4, 1);
    expect((await assignmentOf(4, day(0))).status).toBe('SCHEDULED');
    expect((await assignments({ staffId: 4 })).filter((a) => a.shiftAssignmentId !== started.shiftAssignmentId && a.workDate > day(0) && a.status === 'CANCELLED').length).toBeGreaterThan(0);
    const snapshot = JSON.stringify(await assignments());
    staffLeftBranch(6, 2);
    staffLeftBranch(99, 1);
    expect(JSON.stringify(await assignments())).toBe(snapshot);
  });
});

// ─── BR-NS-09 qua identity-admin.mock: chỉ vô hiệu hóa (Tài khoản#5) và điều chuyển (BR-QT-06) phát StaffLeftBranchEvent (06 §3), sau mọi guard ───
// Dùng nhân viên TẠO LÚC CHẠY (state localStorage, reset mỗi test): nhân viên seed của identity-admin.mock giữ status / chi nhánh ở biến module nên vô hiệu hóa hay điều chuyển chúng làm lệch các test sau.

describe('BR-NS-09: identity-admin gọi staffLeftBranch khi vô hiệu hóa / điều chuyển', () => {
  const REASON = 'Nhân viên không còn làm việc tại chi nhánh';
  const future = (list: ShiftAssignment[]) => list.filter((a) => a.workDate >= day(0)); // hôm nay có ca chiều 13:00 chưa bắt đầu (đồng hồ ghim 09:00)

  /** VET mới ở chi nhánh 1 có 3 phân ca: hôm nay ca chiều (#2), ngày mai và ngày kia ca sáng (#1). */
  async function scheduledVet() {
    const created = await post<{ accountId: number }>('chain', '/api/staff', { email: 'vet.moi@new.vn', phone: '0900000077', fullName: 'BS. Mới', role: 'VET', branchId: 1 });
    await assign(created.accountId, 2, day(0));
    await assign(created.accountId, 1, day(1));
    await assign(created.accountId, 1, day(2));
    return created.accountId;
  }
  const statusesOf = async (staffId: number) => (await assignments({ staffId })).map((a) => a.status);

  it('vô hiệu hóa: mọi phân ca chưa bắt đầu → CANCELLED do hệ thống (lý do BR-NS-09)', async () => {
    const id = await scheduledVet();
    expect(await statusesOf(id)).toEqual(['SCHEDULED', 'SCHEDULED', 'SCHEDULED']);
    const res = await send('chain', 'post', `/api/staff/${id}/disable`);
    expect([res.status, res.data.data.status]).toEqual([200, 'DISABLED']);
    const after = await assignments({ staffId: id });
    expect(after).toHaveLength(3);
    for (const a of future(after)) expect(a, a.workDate).toMatchObject({ status: 'CANCELLED', reason: REASON, pendingRequestId: null });
  });

  it('điều chuyển sang chi nhánh khác: phân ca ở chi nhánh cũ → CANCELLED; người khác ở chi nhánh cũ không bị đụng', async () => {
    const id = await scheduledVet();
    const bystander = JSON.stringify(await assignments({ staffId: 5 }));
    const res = await send('chain', 'post', `/api/staff/${id}/transfer`, { branchId: 2 });
    expect([res.status, res.data.data.branchId]).toEqual([200, 2]);
    expect(await statusesOf(id)).toEqual(['CANCELLED', 'CANCELLED', 'CANCELLED']);
    expect(JSON.stringify(await assignments({ staffId: 5 }))).toBe(bystander);
  });

  it('không phát khi: khóa tài khoản (UC09), đổi chức vụ, điều chuyển cùng chi nhánh, vô hiệu hóa bị BR-QT-12 chặn (đang khóa) — phân ca nguyên vẹn', async () => {
    const id = await scheduledVet();
    const scheduled = ['SCHEDULED', 'SCHEDULED', 'SCHEDULED'];
    expect((await send('admin', 'post', `/api/accounts/${id}/lock`, { reason: 'Thử khóa' })).status).toBe(200);
    expect(await statusesOf(id)).toEqual(scheduled);
    ruleFailure(await rejected('chain', 'post', `/api/staff/${id}/disable`), 'BR-QT-12');
    expect(await statusesOf(id)).toEqual(scheduled);
    expect((await send('admin', 'post', `/api/accounts/${id}/unlock`, { reason: 'Mở khóa' })).status).toBe(200);
    expect((await send('chain', 'post', `/api/staff/${id}/transfer`, { branchId: 1 })).status).toBe(200); // cùng chi nhánh: no-op
    expect((await send('chain', 'post', `/api/staff/${id}/change-role`, { role: 'CARETAKER' })).status).toBe(200);
    expect(await statusesOf(id)).toEqual(scheduled);
  });

  it('vô hiệu hóa lần hai (409) không phát lại: ca giữ nguyên trạng thái đã hủy, không thông báo thêm', async () => {
    const id = await scheduledVet();
    expect((await send('chain', 'post', `/api/staff/${id}/disable`)).status).toBe(200);
    const once = JSON.stringify(await assignments({ staffId: id }));
    expect((await rejected('chain', 'post', `/api/staff/${id}/disable`)).status).toBe(409);
    expect(JSON.stringify(await assignments({ staffId: id }))).toBe(once);
  });
});
