import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/api-error';
import { identityApi } from '../api/identity.api';
import { visitApi } from '../api/visit.api';
import type { Appointment } from '../types/appointment';
import { ALL_ROLES, type Role } from '../types/auth';
import type { StaffResponse } from '../types/identity';
import { visitPermissions, type Visit, type VisitAction } from '../types/visit';
import {
  VISIT_REFRESH_MS,
  useArrivals,
  useAssignVisit,
  useAssignableStaff,
  useCallVisit,
  useCancelVisit,
  useCheckIn,
  useMarkEmergency,
  useMoveVisit,
  useMyQueue,
  useReassignVisit,
  useVisit,
  useVisitQueue,
  visitErrorMessage,
} from './useVisits';

const visit = (visitId: number, over: Partial<Visit> = {}): Visit => ({
  visitId,
  code: `TN-2610-${String(visitId).padStart(5, '0')}`,
  branchId: 1,
  customerId: 1,
  customerName: 'Khách',
  petId: 1,
  petName: 'Mochi',
  species: 'CAT',
  appointmentId: null,
  serviceGroup: 'MEDICAL',
  status: 'WAITING',
  priorityClass: 'WALK_IN',
  queueSortKey: '2026-10-23T03:40:00Z',
  assigneeId: null,
  assigneeName: null,
  needsReassign: false,
  orderId: 1,
  checkedInAt: '2026-10-23T03:40:00Z',
  calledAt: null,
  completedAt: null,
  cancelledAt: null,
  cancelReason: null,
  ...over,
});

const staff = (accountId: number, over: Partial<StaffResponse> = {}): StaffResponse => ({
  accountId,
  email: `nv${accountId}@store1.vn`,
  phone: '0900000000',
  fullName: `Nhân viên ${accountId}`,
  role: 'VET',
  status: 'ACTIVE',
  isLocked: false,
  branchId: 1,
  mustChangePassword: false,
  online: true,
  ...over,
});

const apiError = (status: number, errorCode: ApiError['errorCode'], message = 'x') => new ApiError(errorCode, message, status);
const emptyPage = { content: [], page: 0, size: 100, totalElements: 0, totalPages: 1 };

let client: QueryClient;
const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } });
});
afterEach(() => vi.restoreAllMocks());

const flush = () => new Promise((r) => setTimeout(r, 20));

describe('visitPermissions: ma trận vai trò đã dò trên BE thật', () => {
  const EXPECTED: Record<VisitAction, Role[]> = {
    checkIn: ['RECEPTIONIST'],
    queue: ['BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
    myQueue: ['VET', 'CARETAKER'],
    detail: ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
    assign: ['RECEPTIONIST'],
    move: ['RECEPTIONIST'],
    markEmergency: ['RECEPTIONIST', 'VET'],
    call: ['VET', 'CARETAKER'],
    reassign: ['BRANCH_MANAGER', 'SUPER_MANAGER'],
    cancel: ['RECEPTIONIST'],
    arrivals: ['RECEPTIONIST', 'BRANCH_MANAGER'],
    // T37: bệnh án, cân nặng, bản bổ sung, hoàn tất lượt, tiêm chủng (docs/api/visit-v1.md mục B, ADR-0067, 0070).
    viewRecord: ['VET', 'CARETAKER'],
    writeRecord: ['VET'],
    addendum: ['VET'],
    complete: ['VET', 'CARETAKER'],
    vaccinate: ['VET'],
  };

  it.each(Object.keys(EXPECTED) as VisitAction[])('%s', (action) => {
    const allowed = ALL_ROLES.filter((role) => visitPermissions(role)[action]);
    expect([...allowed].sort()).toEqual([...EXPECTED[action]].sort());
  });

  it('chưa đăng nhập: không thao tác nào', () => {
    expect(Object.values(visitPermissions(null)).every((v) => v === false)).toBe(true);
    expect(Object.values(visitPermissions(undefined)).every((v) => v === false)).toBe(true);
  });
});

describe('hook đọc: chỉ gọi API khi enabled (vai trò không có quyền thì UI truyền false, tránh 403)', () => {
  beforeEach(() => {
    vi.spyOn(visitApi, 'listQueue').mockResolvedValue([visit(1)]);
    vi.spyOn(visitApi, 'getMyQueue').mockResolvedValue([visit(2)]);
    vi.spyOn(visitApi, 'getVisit').mockResolvedValue(visit(3));
    vi.spyOn(visitApi, 'listArrivals').mockResolvedValue(emptyPage);
    vi.spyOn(identityApi, 'listStaff').mockResolvedValue(emptyPage);
  });

  it('enabled = false: không có request nào', async () => {
    renderHook(() => useVisitQueue({}, false), { wrapper });
    renderHook(() => useMyQueue(false), { wrapper });
    renderHook(() => useVisit(3, false), { wrapper });
    renderHook(() => useArrivals(false), { wrapper });
    renderHook(() => useAssignableStaff('VET', false), { wrapper });
    await flush();
    expect(visitApi.listQueue).not.toHaveBeenCalled();
    expect(visitApi.getMyQueue).not.toHaveBeenCalled();
    expect(visitApi.getVisit).not.toHaveBeenCalled();
    expect(visitApi.listArrivals).not.toHaveBeenCalled();
    expect(identityApi.listStaff).not.toHaveBeenCalled();
  });

  it('useVisit chưa chọn lượt (null) thì không gọi', async () => {
    renderHook(() => useVisit(null), { wrapper });
    await flush();
    expect(visitApi.getVisit).not.toHaveBeenCalled();
  });

  it('enabled mặc định true: gọi đúng hàm với đúng tham số', async () => {
    const queue = renderHook(() => useVisitQueue({ status: 'WAITING' }), { wrapper });
    const mine = renderHook(() => useMyQueue(), { wrapper });
    const one = renderHook(() => useVisit(3), { wrapper });
    await waitFor(() => expect(queue.result.current.data).toHaveLength(1));
    await waitFor(() => expect(mine.result.current.data).toHaveLength(1));
    await waitFor(() => expect(one.result.current.data?.visitId).toBe(3));
    expect(visitApi.listQueue).toHaveBeenCalledWith({ status: 'WAITING' });
    expect(visitApi.getVisit).toHaveBeenCalledWith(3);
  });

  it('useArrivals trả mảng lịch hẹn (bóc content)', async () => {
    const appointment = { appointmentId: 7 } as Appointment;
    vi.spyOn(visitApi, 'listArrivals').mockResolvedValue({ ...emptyPage, content: [appointment], totalElements: 1 });
    const { result } = renderHook(() => useArrivals(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([appointment]));
  });

  it('gốc khóa là ["visits", …]; hàng đợi, hàng đợi của tôi, danh sách chờ check-in tự làm mới ~10 giây thay cho đồng bộ localStorage', async () => {
    renderHook(() => useVisitQueue({}), { wrapper });
    renderHook(() => useMyQueue(), { wrapper });
    renderHook(() => useArrivals(), { wrapper });
    await flush();
    const queries = client.getQueryCache().getAll();
    expect(queries).toHaveLength(3);
    for (const q of queries) {
      expect(q.queryKey[0]).toBe('visits');
      expect((q.observers[0].options as { refetchInterval?: number }).refetchInterval).toBe(VISIT_REFRESH_MS);
    }
    expect(VISIT_REFRESH_MS).toBeGreaterThanOrEqual(5_000);
    expect(VISIT_REFRESH_MS).toBeLessThanOrEqual(15_000);
  });
});

describe('lệnh ghi: làm mới visits, appointments, sales (cả khi lỗi, vì 409 = trạng thái đã đổi ở nơi khác)', () => {
  const ROOTS = [['visits'], ['appointments'], ['sales']];
  type Mutation = { mutateAsync: (a: never) => Promise<unknown> };

  async function run(hook: () => Mutation, args: unknown) {
    const spy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(hook, { wrapper });
    await act(async () => {
      await result.current.mutateAsync(args as never).catch(() => undefined);
    });
    return spy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey);
  }

  const MUTATIONS: [string, () => Mutation, unknown, keyof typeof visitApi][] = [
    ['useCheckIn', useCheckIn as never, { petId: 1, serviceId: 1 }, 'checkIn'],
    ['useAssignVisit', useAssignVisit as never, { visitId: 1, assigneeId: 5 }, 'assign'],
    ['useMoveVisit', useMoveVisit as never, { visitId: 1, placeBeforeVisitId: 2 }, 'move'],
    ['useMarkEmergency', useMarkEmergency as never, 1, 'markEmergency'],
    ['useCallVisit', useCallVisit as never, 1, 'call'],
    ['useReassignVisit', useReassignVisit as never, { visitId: 1, assigneeId: 6, reason: 'x' }, 'reassign'],
    ['useCancelVisit', useCancelVisit as never, { visitId: 1, reason: 'x' }, 'cancel'],
  ];

  it.each(MUTATIONS)('%s: thành công làm mới cả ba gốc', async (_name, hook, args, method) => {
    vi.spyOn(visitApi, method).mockResolvedValue(visit(1) as never);
    const keys = await run(hook, args);
    for (const root of ROOTS) expect(keys).toContainEqual(root);
  });

  it.each(MUTATIONS)('%s: lỗi 409 cũng làm mới cả ba gốc', async (_name, hook, args, method) => {
    vi.spyOn(visitApi, method).mockRejectedValue(apiError(409, 'INVALID_STATE_TRANSITION'));
    const keys = await run(hook, args);
    for (const root of ROOTS) expect(keys).toContainEqual(root);
  });

  it('chuyển đúng đối số cho API: assign / move / reassign / cancel / call / markEmergency', async () => {
    const calls: Record<string, unknown[]> = {};
    for (const method of ['assign', 'move', 'reassign', 'cancel', 'call', 'markEmergency'] as const) {
      vi.spyOn(visitApi, method).mockImplementation(((...a: unknown[]) => {
        calls[method] = a;
        return Promise.resolve(visit(1));
      }) as never);
    }
    await run(useAssignVisit as never, { visitId: 4, assigneeId: 5 });
    await run(useMoveVisit as never, { visitId: 4, placeBeforeVisitId: 9 });
    await run(useReassignVisit as never, { visitId: 4, assigneeId: 6, reason: 'Nghỉ' });
    await run(useCancelVisit as never, { visitId: 4, reason: 'Bận' });
    expect(calls.cancel).toEqual([4, { reason: 'Bận' }]);
    await run(useCancelVisit as never, { visitId: 5 });
    await run(useCallVisit as never, 4);
    await run(useMarkEmergency as never, 4);
    expect(calls.assign).toEqual([4, { assigneeId: 5 }]);
    expect(calls.move).toEqual([4, { placeBeforeVisitId: 9 }]);
    expect(calls.reassign).toEqual([4, { assigneeId: 6, reason: 'Nghỉ' }]);
    expect(calls.cancel).toEqual([5, undefined]);
    expect(calls.call).toEqual([4]);
    expect(calls.markEmergency).toEqual([4]);
  });

  it('check-in xong thì danh sách chờ check-in (useArrivals) được tải lại', async () => {
    const list = vi.spyOn(visitApi, 'listArrivals').mockResolvedValue(emptyPage);
    vi.spyOn(visitApi, 'checkIn').mockResolvedValue(visit(1));
    const arrivals = renderHook(() => useArrivals(), { wrapper });
    await waitFor(() => expect(arrivals.result.current.isSuccess).toBe(true));
    expect(list).toHaveBeenCalledTimes(1);
    const mutation = renderHook(() => useCheckIn(), { wrapper });
    await act(async () => {
      await mutation.result.current.mutateAsync({ petId: 1, serviceId: 1 });
    });
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
  });

  it('đặt Visit trả về vào cache chi tiết (AssignResult lấy .visit)', async () => {
    vi.spyOn(visitApi, 'assign').mockResolvedValue({ visit: visit(8, { assigneeId: 5 }), assigneeOffline: true });
    await run(useAssignVisit as never, { visitId: 8, assigneeId: 5 });
    expect(client.getQueryData<Visit>(['visits', 'detail', 8])?.assigneeId).toBe(5);
  });
});

describe('useAssignableStaff (BR-TN-05, BR-TN-06; ASSUMPTION GET /api/staff mở cho lễ tân, chờ BE-1)', () => {
  it('gọi identityApi.listStaff({ role, status: ACTIVE, size: 100 }); thêm branchId khi quản lý chuỗi chọn chi nhánh của lượt', async () => {
    const spy = vi.spyOn(identityApi, 'listStaff').mockResolvedValue(emptyPage);
    renderHook(() => useAssignableStaff('VET', true), { wrapper });
    renderHook(() => useAssignableStaff('CARETAKER', true, 2), { wrapper });
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(2));
    expect(spy).toHaveBeenCalledWith({ role: 'VET', status: 'ACTIVE', size: 100 });
    expect(spy).toHaveBeenCalledWith({ role: 'CARETAKER', status: 'ACTIVE', size: 100, branchId: 2 });
  });

  it('người online xếp trước; người offline vẫn chọn được kèm cờ cảnh báo; bỏ tài khoản bị khóa (status vẫn ACTIVE) và sai chức vụ', async () => {
    vi.spyOn(identityApi, 'listStaff').mockResolvedValue({
      ...emptyPage,
      content: [
        staff(3, { online: false }),
        staff(8, { online: true }),
        staff(7, { isLocked: true }),
        staff(6, { role: 'CARETAKER' }),
        staff(5, { online: true }),
      ],
      totalElements: 5,
    });
    const { result } = renderHook(() => useAssignableStaff('VET', true), { wrapper });
    await waitFor(() => expect(result.current.staff).toHaveLength(3));
    expect(result.current.staff.map((s) => [s.accountId, s.online, s.offlineWarning])).toEqual([
      [5, true, false],
      [8, true, false],
      [3, false, true],
    ]);
    expect(result.current.staffPickerBlocked).toBe(false);
  });

  it('BE trả 403 (lễ tân chưa được mở GET /api/staff): staffPickerBlocked = true, danh sách rỗng, không gọi lại', async () => {
    const spy = vi.spyOn(identityApi, 'listStaff').mockRejectedValue(apiError(403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này'));
    const { result } = renderHook(() => useAssignableStaff('VET', true), { wrapper });
    await waitFor(() => expect(result.current.staffPickerBlocked).toBe(true));
    expect(result.current.staff).toEqual([]);
    await flush();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('lỗi khác 403 không bị coi là bị chặn', async () => {
    vi.spyOn(identityApi, 'listStaff').mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));
    const { result } = renderHook(() => useAssignableStaff('VET', true), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.staffPickerBlocked).toBe(false);
  });
});

describe('visitErrorMessage', () => {
  const rule = (ruleId: string, text: string) => apiError(400, 'BUSINESS_RULE_VIOLATION', `${text} (${ruleId})`);

  const conflict = (text: string) => apiError(409, 'INVALID_STATE_TRANSITION', text);

  it('409 lịch hẹn tách theo trạng thái cũ: đã CHECKED_IN → xem hàng đợi (KHÔNG tiếp nhận thêm); NO_SHOW → tiếp nhận như khách vãng lai; còn lại → không tiếp nhận được', () => {
    const checkedIn = visitErrorMessage(conflict('Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang CHECKED_IN'));
    expect(checkedIn).toContain('đã được tiếp nhận');
    expect(checkedIn).toContain('hàng đợi');
    expect(checkedIn).not.toContain('khách vãng lai');
    const noShow = visitErrorMessage(conflict('Không thể chuyển trạng thái lịch hẹn từ NO_SHOW sang CHECKED_IN'));
    expect(noShow).toContain('không đến');
    expect(noShow).toContain('khách vãng lai');
    for (const from of ['CANCELLED', 'COMPLETED']) {
      const done = visitErrorMessage(conflict(`Không thể chuyển trạng thái lịch hẹn từ ${from} sang CHECKED_IN`));
      expect(done, from).toContain('đã hủy hoặc đã hoàn tất');
      expect(done, from).not.toContain('khách vãng lai');
    }
    expect(new Set([checkedIn, noShow]).size).toBe(2);
  });

  it('409 hạng ưu tiên → đã là cấp cứu; hủy lượt đã gọi (BR-TN-07) → nhân viên phải hoàn tất theo BR-KB-02; còn lại → đã đổi ở nơi khác', () => {
    expect(visitErrorMessage(conflict('Không thể chuyển trạng thái hạng ưu tiên của lượt từ EMERGENCY sang EMERGENCY'))).toBe('Lượt này đã là ca cấp cứu.');
    const called = visitErrorMessage(conflict('Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang CANCELLED'), 'cancel');
    expect(called).toContain('đã được gọi');
    expect(called).toContain('hoàn tất');
    expect(visitErrorMessage(conflict('Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang CANCELLED'))).toBe(called);
    const changed = visitErrorMessage(conflict('Không thể chuyển trạng thái lượt tiếp nhận từ CANCELLED sang CANCELLED'));
    expect(changed).toContain('tải lại');
    expect(changed).not.toBe(called);
    expect(visitErrorMessage(conflict('Không thể chuyển trạng thái lượt tiếp nhận từ WAITING sang IN_PROGRESS'))).toContain('tải lại');
  });

  it('403 phạm vi khi gọi lượt = chưa được gán cho mình; CONCURRENCY_CONFLICT = vừa được gán lại', () => {
    const scope = apiError(403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');
    expect(visitErrorMessage(scope, 'call')).toBe('Lượt này không được gán cho bạn nên bạn không gọi được.');
    expect(visitErrorMessage(scope, 'reassign')).toContain('quản lý chi nhánh');
    expect(visitErrorMessage(scope)).toContain('chi nhánh');
    expect(visitErrorMessage(apiError(409, 'CONCURRENCY_CONFLICT', 'x'))).toContain('gán lại');
  });

  it('rule: BR-TN-08 viết lại dễ hiểu; thú mất là BR-TN-01; các rule còn lại giữ thông điệp của BE bỏ mã', () => {
    expect(visitErrorMessage(rule('BR-TN-08', 'Người phụ trách vẫn đang hoạt động; chỉ gán lại'))).toContain('không còn');
    expect(visitErrorMessage(rule('BR-TN-01', 'Thú cưng đã mất, không tiếp nhận được'))).toBe('Thú cưng đã mất, không tiếp nhận được');
    expect(visitErrorMessage(rule('BR-TN-02', 'Lịch hẹn thuộc chi nhánh khác'))).toBe('Lịch hẹn thuộc chi nhánh khác');
    expect(visitErrorMessage(rule('BR-TN-05', 'Nhân viên không hoạt động tại chi nhánh này'))).toBe('Nhân viên không hoạt động tại chi nhánh này');
    expect(visitErrorMessage(rule('BR-CN-05', 'Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu'))).toBe('Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu');
    expect(visitErrorMessage(rule('BR-KH-05', 'Không thể đánh dấu đã mất: thú cưng đang có lượt khám chưa kết thúc'))).toContain('lượt khám');
  });

  it('validation theo trường và lỗi lạ', () => {
    expect(visitErrorMessage(apiError(400, 'VALIDATION_FAILED', 'reason: Bắt buộc nhập lý do gán lại'))).toBe('Bắt buộc nhập lý do gán lại');
    expect(visitErrorMessage(apiError(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy lượt tiếp nhận #9'))).toContain('Không tìm thấy');
    expect(visitErrorMessage(new Error('boom'))).toContain('Đã có lỗi');
  });
});

describe('visitErrorMessage: bệnh án, hoàn tất, tiêm chủng (T37) — 409 và 403 nói theo thao tác, không dùng câu "đã tải lại hàng đợi"', () => {
  const rule = (ruleId: string, text: string) => apiError(400, 'BUSINESS_RULE_VIOLATION', `${text} (${ruleId})`);
  const conflictFrom = (from: string, to: string) => apiError(409, 'INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái lượt tiếp nhận từ ${from} sang ${to}`);
  const scope = apiError(403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này');

  it('ghi bệnh án / cân nặng: lượt đã hoàn tất → bệnh án đã khóa, dùng "Thêm bổ sung"; chưa gọi → gọi lượt trước; đã hủy', () => {
    const locked = visitErrorMessage(conflictFrom('COMPLETED', 'IN_PROGRESS'), 'writeRecord');
    expect(locked).toContain('đã khóa');
    expect(locked).toContain('Thêm bổ sung');
    expect(visitErrorMessage(conflictFrom('WAITING', 'IN_PROGRESS'), 'writeRecord')).toContain('Gọi');
    expect(visitErrorMessage(conflictFrom('CANCELLED', 'IN_PROGRESS'), 'writeRecord')).toContain('đã hủy');
    expect(visitErrorMessage(scope, 'writeRecord')).toContain('được gán');
    for (const action of ['writeRecord', 'vaccinate'] as const) expect(visitErrorMessage(conflictFrom('COMPLETED', 'IN_PROGRESS'), action)).not.toContain('tải lại hàng đợi');
  });

  it('bản bổ sung khi lượt chưa hoàn tất → sửa trực tiếp bệnh án', () => {
    for (const from of ['IN_PROGRESS', 'WAITING', 'CANCELLED']) {
      const text = visitErrorMessage(conflictFrom(from, 'COMPLETED'), 'addendum');
      expect(text, from).toContain('chưa hoàn tất');
      expect(text, from).toContain('sửa trực tiếp');
    }
  });

  it('hoàn tất lượt: đã xong rồi / chưa ở trạng thái đang khám; chỉ người được gán; BR-TN-05 và BR-KB-02 giữ thông điệp của BE bỏ mã', () => {
    expect(visitErrorMessage(conflictFrom('COMPLETED', 'COMPLETED'), 'complete')).toContain('đã được hoàn tất');
    expect(visitErrorMessage(conflictFrom('WAITING', 'COMPLETED'), 'complete')).toContain('đang khám');
    expect(visitErrorMessage(scope, 'complete')).toContain('người được gán');
    expect(visitErrorMessage(rule('BR-KB-02', 'Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất'), 'complete')).toBe('Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất');
    expect(visitErrorMessage(rule('BR-TN-05', 'Chức vụ hiện tại không phụ trách được nhóm dịch vụ của lượt — cần gán lại lượt'), 'complete')).toContain('cần gán lại lượt');
  });

  it('tiêm chủng: lượt đã hoàn tất → sai sót ghi bằng bản bổ sung; 403 = không phải bác sĩ được gán / đã ghi mũi; BR-KB-04, BR-KB-06, BR-KH-04 giữ thông điệp của BE', () => {
    const locked = visitErrorMessage(conflictFrom('COMPLETED', 'IN_PROGRESS'), 'vaccinate');
    expect(locked).toContain('đã hoàn tất');
    expect(locked).toContain('bản bổ sung');
    expect(visitErrorMessage(scope, 'vaccinate')).toContain('đã ghi mũi');
    expect(visitErrorMessage(rule('BR-KB-04', 'Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh'), 'vaccinate')).toBe('Sản phẩm vaccine không còn tồn khả dụng tại chi nhánh');
    expect(visitErrorMessage(rule('BR-KB-06', 'Ngày tái khám phải sau ngày khám và không quá 180 ngày'), 'writeRecord')).toBe('Ngày tái khám phải sau ngày khám và không quá 180 ngày');
    expect(visitErrorMessage(rule('BR-KH-04', 'Cân nặng phải lớn hơn 0'), 'writeRecord')).toBe('Cân nặng phải lớn hơn 0');
  });
});
