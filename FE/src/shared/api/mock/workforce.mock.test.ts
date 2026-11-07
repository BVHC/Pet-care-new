import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, pinClock } from '../../../test/boarding-harness';
import type { PageResponse } from '../../types';
import type { AppNotification } from '../../types/care';
import type { ShiftAssignment, ShiftDefinition, ShiftRequest } from '../../types/workforce';
import { addDays } from '../../utils/branch-schedule';
import { ruleIdOf } from '../api-error';
import { staffLeftBranch } from './workforce.mock';
import { installMockApi } from './mock-api';

// Nhân sự (workforce-v1 #1–14) — BE tầng 3 chưa cài, mock theo ASSUMPTION W1–W10 ở đầu workforce.mock.ts. Đồng hồ ghim ở thứ Sáu 23/10/2026 09:00 giờ Việt Nam (ca sáng 07:30 hôm nay
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

  it('giờ bắt đầu không nhỏ hơn giờ kết thúc → 400 BR-NS-01; tên trùng được báo trước giờ sai (thứ tự của yaml)', async () => {
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lạ', startTime: '12:00', endTime: '12:00' }), 'BR-NS-01', /Giờ bắt đầu phải nhỏ hơn/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lạ', startTime: '18:00', endTime: '06:00' }), 'BR-NS-01');
    ruleFailure(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca sáng', startTime: '18:00', endTime: '06:00' }), 'BR-NS-01', /đã tồn tại/);
  });

  it('VALIDATION_FAILED: thiếu / trống / quá 60 ký tự / sai dạng giờ (kể cả giờ 24–29 mà pattern của yaml cho qua)', async () => {
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', {}), 400, 'VALIDATION_FAILED', /name: .*startTime: .*endTime: /);
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: '   ', startTime: '08:00', endTime: '09:00' }), 400, 'VALIDATION_FAILED', /^name: /);
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 'x'.repeat(61), startTime: '08:00', endTime: '09:00' }), 400, 'VALIDATION_FAILED', /^name: .*60/);
    expect((await send('manager', 'post', '/api/shift-definitions', { name: 'x'.repeat(60), startTime: '08:00', endTime: '09:00' })).status).toBe(201);
    for (const bad of ['8:30', '08:60', '24:00', '29:30', '08-30', ' ']) {
      expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 'Ca lỗi', startTime: bad, endTime: '23:59' }), 400, 'VALIDATION_FAILED', /^startTime: /);
    }
    expect((await send('manager', 'post', '/api/shift-definitions', { name: 'Ca cuối ngày', startTime: '00:00', endTime: '23:59' })).status).toBe(201);
  });

  it('MALFORMED_REQUEST: body không phải object, trường sai kiểu; đến TRƯỚC vai trò, còn VALIDATION_FAILED đến SAU vai trò', async () => {
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', { name: 123, startTime: '08:00', endTime: '09:00' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-definitions', '[1,2]'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-definitions'), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/shift-definitions', { name: 123 }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('customer', 'post', '/api/shift-definitions', {}), 403, 'ACCESS_DENIED');
  });
});

describe('sửa / ngừng dùng ca (#3, BR-NS-01)', () => {
  const patch = (who: Who, id: number | string, body: unknown) => send(who, 'patch', `/api/shift-definitions/${id}`, body);

  it('chỉ trường có trong body được đổi; {} và null giữ nguyên; đổi tên, không đổi giờ ca đã có phân ca', async () => {
    const before = (await definitions()).find((d) => d.shiftDefinitionId === 1)!;
    expect((await patch('manager', 1, {})).data.data).toEqual(before);
    expect((await patch('manager', 1, { name: null, startTime: null, endTime: null, active: null })).data.data).toEqual(before);
    expect((await patch('manager', 1, { name: ' Ca sáng sớm ' })).data.data).toEqual({ ...before, name: 'Ca sáng sớm' });
    // Gửi lại đúng giờ hiện tại coi như không đổi giờ.
    expect((await patch('manager', 1, { startTime: '07:30', endTime: '12:00' })).status).toBe(200);
  });

  it('sửa giờ ca đã có phân ca → 400 BR-NS-01; ca chưa có phân ca thì sửa được', async () => {
    ruleFailure((await failure(patch('manager', 1, { startTime: '07:00' }))) as AxiosResponse, 'BR-NS-01', /không sửa được giờ/);
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

  it('ngừng dùng: bị chặn khi còn phân ca SCHEDULED từ hôm nay; không bị chặn khi chỉ còn phân ca đã qua / đã hủy; bật lại được', async () => {
    ruleFailure(await failure(patch('manager', 1, { active: false })), 'BR-NS-01', /chưa ngừng dùng được/);
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
    expectFail(await failure(patch('manager', 999, {})), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(patch('manager2', 1, { name: 'Ca trộm' })), 403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
    expectFail(await failure(patch('manager2', 1, { name: '' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { name: ' ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { name: 'x'.repeat(61) })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 1, { startTime: '25:00' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(patch('manager', 'abc', {})), 400, 'MALFORMED_REQUEST', "Tham số 'shiftDefinitionId' sai kiểu dữ liệu");
    expectFail(await failure(patch('manager', 1, { active: 'false' })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(patch('customer', 'abc', {})), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(patch('customer', 1, {})), 403, 'ACCESS_DENIED');
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
      ['branchId', 'endTime', 'pendingRequestId', 'reason', 'shiftAssignmentId', 'shiftDefinitionId', 'shiftName', 'staffId', 'staffName', 'startTime', 'status', 'workDate'],
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
      status: 'SCHEDULED', reason: null, pendingRequestId: null,
    });
    expect((await mine('doctor3')).map((a) => a.shiftAssignmentId)).toEqual([res.data.data.shiftAssignmentId]);
    expect((await assign(22, 2, day(0))).workDate).toBe(day(0));
    // Mọi vai A06–A08 được phân: lễ tân mới (8), chăm sóc (6) cũng vậy.
    expect((await assign(8, 1, day(3))).staffName).toBe('Lễ tân mới');
  });

  it('trùng đúng cặp ca – ngày, chồng giờ phân ca khác trong ngày → 400 BR-NS-02 nêu ca đang chồng; chạm đầu mút thì không chồng; ngày khác thì được', async () => {
    await assign(22, 1, day(2)); // 07:30–12:00
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(2) }), 'BR-NS-02', /đã được phân ca "Ca sáng"/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 3, workDate: day(2) }), 'BR-NS-02', /Chồng giờ với ca "Ca sáng" \(07:30–12:00\)/);
    await assign(22, 2, day(2)); // 13:00–17:30, không chồng
    await assign(22, 4, day(2)); // 17:30–21:00 chạm đầu mút ca chiều
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 3, workDate: day(2) }), 'BR-NS-02', /Chồng giờ/);
    expect((await assign(22, 3, day(3))).shiftName).toBe('Ca hành chính');
    // Phân ca đã hủy không còn tính khi kiểm chồng giờ.
    const sang = await assignmentOf(22, day(2));
    expect(sang.shiftName).toBe('Ca sáng');
    await post('manager', `/api/shift-assignments/${sang.shiftAssignmentId}/cancel`, { reason: 'Đổi lịch' });
    expect((await assign(22, 1, day(2))).status).toBe('SCHEDULED');
  });

  it('ngày quá khứ, ca ngừng dùng, người không phải A06–A08 → 400 BR-NS-02', async () => {
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: day(-1) }), 'BR-NS-02', /quá khứ/);
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 5, workDate: day(1) }), 'BR-NS-02', /ngừng dùng/);
    // Quản lý chính chi nhánh (A05) thuộc chi nhánh nhưng không phải A06–A08.
    ruleFailure(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 3, shiftDefinitionId: 1, workDate: day(1) }), 'BR-NS-02', /lễ tân, bác sĩ thú y hoặc nhân viên chăm sóc/);
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
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', {}), 400, 'VALIDATION_FAILED', /staffId: .*shiftDefinitionId: .*workDate: /);
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22, shiftDefinitionId: 1, workDate: '2026-02-30' }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: '22', shiftDefinitionId: 1, workDate: day(1) }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('manager', 'post', '/api/shift-assignments', { staffId: 22.5, shiftDefinitionId: 1, workDate: day(1) }), 400, 'MALFORMED_REQUEST');
    expectFail(await rejected('desk', 'post', '/api/shift-assignments', { staffId: 22 }), 403, 'ACCESS_DENIED');
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
    expect(rejectedRequest).toMatchObject({ status: 'REJECTED', decidedBy: null, decisionNote: 'Phân ca đã bị hủy.' });
    expect(rejectedRequest.closedAt).toEqual(expect.any(String));
    expect(await pendingList()).toHaveLength(1);
  });

  it('ca đã bắt đầu → 400 BR-NS-02 (dùng ghi vắng đột xuất); đến TRƯỚC 409 nên phân ca đã qua cũng ra 400', async () => {
    const started = await assignmentOf(4, day(0)); // Ca sáng 07:30, bây giờ 09:00
    ruleFailure(await failure(cancel('manager', started.shiftAssignmentId)), 'BR-NS-02', /ghi nhận vắng đột xuất/);
    const absent = await assignmentOf(4, day(-2));
    ruleFailure(await failure(cancel('manager', absent.shiftAssignmentId)), 'BR-NS-02');
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
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED', /^reason: Lý do không được để trống/);
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: '  ' })), 400, 'VALIDATION_FAILED');
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: 'x'.repeat(301) })), 400, 'VALIDATION_FAILED', /300/);
    expectFail(await failure(cancel('manager', target.shiftAssignmentId, { reason: 5 })), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(cancel('manager', 99999)), 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy phân ca #99999');
    expectFail(await failure(cancel('manager2', target.shiftAssignmentId)), 403, 'ACCESS_DENIED_SCOPE_MISMATCH');
    expectFail(await failure(cancel('manager2', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED');
    expectFail(await failure(cancel('manager', 'abc')), 400, 'MALFORMED_REQUEST');
    expectFail(await failure(cancel('desk', target.shiftAssignmentId, {})), 403, 'ACCESS_DENIED');
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
    ruleFailure(await failure(absent('manager', future.shiftAssignmentId)), 'BR-NS-07', /ngày tương lai/);
    const tooOld = await assignmentOf(21, day(-9));
    ruleFailure(await failure(absent('manager', tooOld.shiftAssignmentId)), 'BR-NS-07', /trong 7 ngày/);
    const sevenDays = await assignmentOf(5, day(-7));
    expect((await absent('manager', sevenDays.shiftAssignmentId)).data.data.status).toBe('ABSENT');
  });

  it('yêu cầu PENDING của phân ca tự bị từ chối; ca đã ABSENT / CANCELLED ghi lại → 409 (guard ngày đến trước 409)', async () => {
    // Phân ca hôm nay không có yêu cầu mẫu; tạo yêu cầu cho ca ngày mai rồi để nó qua ngày không thực tế, nên dùng ca T−1 của nhân viên 5 (không có yêu cầu) làm ca 409.
    const yesterday = await assignmentOf(5, day(-1));
    await absent('manager', yesterday.shiftAssignmentId);
    expectFail(await failure(absent('manager', yesterday.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái phân ca từ ABSENT sang ABSENT');
    const already = await assignmentOf(4, day(-2));
    expectFail(await failure(absent('manager', already.shiftAssignmentId)), 409, 'INVALID_STATE_TRANSITION');
    // Ca tương lai đã hủy: guard ngày (400) đến trước 409.
    const cancelled = await assignmentOf(4, day(8));
    ruleFailure(await failure(absent('manager', cancelled.shiftAssignmentId)), 'BR-NS-07', /ngày tương lai/);
  });

  it('lý do bắt buộc (VALIDATION_FAILED), 404, 403 phạm vi, vai trò', async () => {
    const target = await assignmentOf(5, day(-1));
    expectFail(await failure(absent('manager', target.shiftAssignmentId, {})), 400, 'VALIDATION_FAILED', /^reason: /);
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
    expect((await notices('manager')).find((n) => n.type === 'SHIFT_REQUEST_SUBMITTED')?.title).toBe('Yêu cầu đổi ca ca làm việc mới');
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
    ruleFailure(await failure(request('desk', { shiftAssignmentId: onLeave.shiftAssignmentId, type: 'LEAVE', reason: 'Nghỉ nữa' })), 'BR-NS-04', /đang chờ làm việc/);
    const pending = await assignmentOf(5, day(3));
    ruleFailure(await failure(request('doctor', { shiftAssignmentId: pending.shiftAssignmentId, type: 'LEAVE', reason: 'Nghỉ nữa' })), 'BR-NS-04', /đang chờ duyệt/);
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
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 5 })), 'BR-NS-04', /ngừng dùng/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 1 })), 'BR-NS-04', /trùng với ca hiện tại/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 3 })), 'BR-NS-04', /Ca đích chồng giờ với ca "Ca chiều" \(13:00–17:30\)/);
    ruleFailure(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 2 })), 'BR-NS-04', /Ca đích chồng giờ/); // chính ca chiều đã phân trong ngày
    expect((await request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 4 })).status).toBe(201); // 17:30–21:00 chỉ chạm đầu mút ca chiều
    expectFail(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 6 })), 404, 'RESOURCE_NOT_FOUND', /ca làm việc #6/);
    expectFail(await failure(request('doctor3', { ...base, type: 'SWAP', targetShiftDefinitionId: 999 })), 404, 'RESOURCE_NOT_FOUND');
  });

  it('phân ca của người khác hoặc không có → 404 (không lộ sự tồn tại); thiếu trường → VALIDATION_FAILED; kiểu sai → MALFORMED_REQUEST', async () => {
    const other = await assignmentOf(6, day(5));
    expectFail(await failure(request('desk', { shiftAssignmentId: other.shiftAssignmentId, type: 'LEAVE', reason: 'x' })), 404, 'RESOURCE_NOT_FOUND', /Không tìm thấy phân ca #\d+ của bạn/);
    expectFail(await failure(request('desk', { shiftAssignmentId: 99999, type: 'LEAVE', reason: 'x' })), 404, 'RESOURCE_NOT_FOUND');
    expectFail(await failure(request('desk', {})), 400, 'VALIDATION_FAILED', /shiftAssignmentId: .*type: .*reason: /);
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
    expect((await mine('doctor')).find((a) => a.workDate === day(3))).toMatchObject({ status: 'ON_LEAVE', pendingRequestId: null });
    const got = (await notices('doctor')).filter((n) => n.type === 'SHIFT_REQUEST_APPROVED');
    expect(got).toHaveLength(1);
    expect(got[0].body).toBe(`Yêu cầu nghỉ ca Ca chiều ngày ${day(3).split('-').reverse().join('/')} tại PetCare Cầu Giấy của bạn đã được quản lý duyệt.`);
    expect(await pendingList()).toHaveLength(1);
  });

  it('duyệt SWAP: phân ca đổi sang ca đích và vẫn SCHEDULED (tên ca hiện tại của yêu cầu theo phân ca mới, W9)', async () => {
    const res = await send('manager', 'post', '/api/shift-requests/2/approve');
    expect(res.data.data).toMatchObject({ status: 'APPROVED', decidedBy: 3, currentShiftName: 'Ca chiều', targetShiftName: 'Ca chiều' });
    expect((await mine('caretaker')).find((a) => a.workDate === day(4))).toMatchObject({ status: 'SCHEDULED', shiftDefinitionId: 2, shiftName: 'Ca chiều', startTime: '13:00', pendingRequestId: null });
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
    ruleFailure(await rejected('manager', 'post', `/api/shift-requests/${r1.shiftRequestId}/approve`), 'BR-NS-06', /chồng giờ với ca "Ca chiều"/);

    const tiny = await newShift('Ca nhỏ', '20:00', '21:00');
    const toi = await assign(23, 4, day(5));
    const r2 = await post<ShiftRequest>('doctor4', '/api/me/shift-requests', { shiftAssignmentId: toi.shiftAssignmentId, type: 'SWAP', targetShiftDefinitionId: tiny.shiftDefinitionId, reason: 'Đổi' });
    await send('manager', 'patch', `/api/shift-definitions/${tiny.shiftDefinitionId}`, { active: false });
    ruleFailure(await rejected('manager', 'post', `/api/shift-requests/${r2.shiftRequestId}/approve`), 'BR-NS-06', /ngừng dùng/);
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
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', {}), 400, 'VALIDATION_FAILED', /^decisionNote: /);
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', { decisionNote: '  ' }), 400, 'VALIDATION_FAILED');
    expectFail(await rejected('manager', 'post', '/api/shift-requests/2/reject', { decisionNote: 'z'.repeat(301) }), 400, 'VALIDATION_FAILED');
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

// ─── Phân quyền 7 vai trò × 14 endpoint, 401, tài khoản phải đổi mật khẩu ───

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
    { name: '#1 GET /shift-definitions', allowed: BRANCH_MANAGER_ONLY, call: (w) => send(w, 'get', '/api/shift-definitions') },
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
  ];

  it('đủ 14 endpoint của hợp đồng', () => {
    expect(endpoints).toHaveLength(14);
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
    ];
    expect(calls).toHaveLength(14);
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
      if (was.status === 'SCHEDULED' && a.workDate >= day(0)) expect(a).toMatchObject({ status: 'CANCELLED', reason: 'Nhân viên không còn làm việc tại chi nhánh (BR-NS-09).', pendingRequestId: null });
      else expect(a.status).toBe(was.status);
    }
    expect(after.find((a) => a.shiftAssignmentId === startedToday.shiftAssignmentId)?.status).toBe('CANCELLED');
    expect(after.find((a) => a.workDate === day(-1))?.status).toBe('SCHEDULED'); // đã qua: giữ nguyên
    const [r1] = (await requestsOf('doctor')).filter((r) => r.shiftRequestId === 1);
    expect(r1).toMatchObject({ status: 'REJECTED', decidedBy: null, decisionNote: 'Nhân viên không còn làm việc tại chi nhánh.' });
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
  const REASON = 'Nhân viên không còn làm việc tại chi nhánh (BR-NS-09).';
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
