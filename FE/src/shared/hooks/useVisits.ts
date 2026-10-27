// React Query cho tiếp nhận, hàng đợi, gọi lượt (visit-v1 #1–9, #11; UC44–UC47): khóa gốc ['visits', …].
// Hàng đợi / hàng đợi của tôi / danh sách chờ check-in tự hỏi lại ~10 giây (thay cho đồng bộ localStorage của clinic-db).
// Phân quyền kiểu `useMyShift(enabled)`: hook đọc nhận `enabled`, UI tính từ `visitPermissions(role)` để vai trò không có quyền không bắn request (BE trả 403).
// Lệnh ghi làm mới ['visits'], ['appointments'] (check-in / hủy đổi lịch hẹn) và ['sales'] (check-in mở Order, hủy hủy Order), kể cả khi lỗi vì 409 nghĩa là
// lượt đã đổi ở nơi khác.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { plainMessage, toApiError } from '../api/api-error';
import { identityApi } from '../api/identity.api';
import { visitApi, type ArrivalsQuery } from '../api/visit.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { Appointment } from '../types/appointment';
import type { PageResponse } from '../types';
import type { StaffResponse } from '../types/identity';
import type { AssignRequest, CheckInRequest, MoveVisitRequest, ReassignRequest, Visit, VisitAction, VisitQueueQuery } from '../types/visit';

export const VISITS_KEY = ['visits'] as const;
export const VISIT_REFRESH_MS = 10_000;

/** Lỗi 4xx không đổi khi thử lại (lỗi axios đổi sang ApiError để đọc mã HTTP). */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;
export const visitQueryRetry = retry;
const live = { ...retry, refetchInterval: VISIT_REFRESH_MS, refetchIntervalInBackground: false } as const;

/** #2 (A05–A08). */
export const useVisitQueue = (query: VisitQueueQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['visits', 'queue', query], queryFn: () => visitApi.listQueue(query), enabled, placeholderData: keepPreviousData, ...live });

/** #3 (VET, CARETAKER). */
export const useMyQueue = (enabled = true) => useQuery({ queryKey: ['visits', 'my-queue'], queryFn: visitApi.getMyQueue, enabled, ...live });

/** #4 (A04–A08). Chưa chọn lượt (null) thì chưa gọi. */
export const useVisit = (visitId: number | null, enabled = true) =>
  useQuery({ queryKey: ['visits', 'detail', visitId], queryFn: () => visitApi.getVisit(visitId!), enabled: enabled && visitId != null, ...retry });

const contentOf = (page: PageResponse<Appointment>) => page.content;

/** Khách đã tới để check-in (appointment-v1 #3 `checkInReady`, lễ tân / BRANCH_MANAGER). Khóa dưới ['visits'] nên check-in làm tải lại. */
export const useArrivals = (enabled = true, query: ArrivalsQuery = {}) =>
  useQuery({ queryKey: ['visits', 'arrivals', query], queryFn: () => visitApi.listArrivals(query), select: contentOf, enabled, ...live });

// ─── Lệnh ghi ───

const visitOf = (result: unknown): Visit | null => {
  const r = result as { visit?: Visit; visitId?: number } | null;
  if (r?.visit) return r.visit;
  return r?.visitId != null ? (r as Visit) : null;
};

/** Đặt Visit trả về (nếu là một Visit hoặc AssignResult) vào cache chi tiết rồi làm mới ba gốc, kể cả khi lỗi. */
export function useVisitMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (result) => {
      const visit = visitOf(result);
      if (visit) queryClient.setQueryData(['visits', 'detail', visit.visitId], visit);
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: VISITS_KEY }),
        queryClient.invalidateQueries({ queryKey: ['appointments'] }),
        queryClient.invalidateQueries({ queryKey: ['sales'] }),
      ]),
  });
}

/** #1 (lễ tân). */
export const useCheckIn = () => useVisitMutation((body: CheckInRequest) => visitApi.checkIn(body));
/** #5 (lễ tân). */
export const useAssignVisit = () => useVisitMutation((args: { visitId: number } & AssignRequest) => visitApi.assign(args.visitId, { assigneeId: args.assigneeId }));
/** #6 (lễ tân). */
export const useMoveVisit = () =>
  useVisitMutation((args: { visitId: number } & MoveVisitRequest) => visitApi.move(args.visitId, { placeBeforeVisitId: args.placeBeforeVisitId }));
/** #7 (lễ tân, VET). */
export const useMarkEmergency = () => useVisitMutation((visitId: number) => visitApi.markEmergency(visitId));
/** #8 (VET, CARETAKER). */
export const useCallVisit = () => useVisitMutation((visitId: number) => visitApi.call(visitId));
/** #9 (BRANCH_MANAGER; SUPER_MANAGER khi chi nhánh hết quản lý ACTIVE). */
export const useReassignVisit = () =>
  useVisitMutation((args: { visitId: number } & ReassignRequest) => visitApi.reassign(args.visitId, { assigneeId: args.assigneeId, reason: args.reason }));
/** #11 (lễ tân). Lý do không bắt buộc: không gửi thì không có body. */
export const useCancelVisit = () =>
  useVisitMutation((args: { visitId: number; reason?: string }) => visitApi.cancel(args.visitId, args.reason === undefined ? undefined : { reason: args.reason }));

// ─── Chọn người gán (BR-TN-05, BR-TN-06) ───

export interface AssignableStaff {
  accountId: number;
  fullName: string;
  role: 'VET' | 'CARETAKER';
  online: boolean;
  /** Offline vẫn chọn được, chỉ cảnh báo (BR-TN-06). */
  offlineWarning: boolean;
}

/** Bỏ tài khoản bị khóa (BE vẫn trả `status: ACTIVE` kèm `isLocked`, gán sẽ 400 BR-TN-05) và sai chức vụ; người online xếp trước. */
function assignableOf(role: AssignableStaff['role'], rows: PageResponse<StaffResponse>): AssignableStaff[] {
  return rows.content
    .filter((s) => s.role === role && s.status === 'ACTIVE' && !s.isLocked)
    .map((s) => ({ accountId: s.accountId, fullName: s.fullName, role, online: s.online, offlineWarning: !s.online }))
    .sort((a, b) => Number(b.online) - Number(a.online) || a.accountId - b.accountId);
}

/**
 * Danh sách người gán lượt: VET cho MEDICAL, CARETAKER cho GROOMING. Dùng identity-v1 #15 `GET /api/staff`.
 * ASSUMPTION chờ BE-1: BE thật hiện loại RECEPTIONIST khỏi #15 (403, đã dò), nên `staffPickerBlocked = true` và UI phải báo "danh sách nhân viên chờ BE-1 mở GET /api/staff
 * cho lễ tân" thay vì để trống. BRANCH_MANAGER / SUPER_MANAGER gọi được; SUPER_MANAGER truyền `branchId` của lượt vì #15 trả cả chuỗi.
 */
export function useAssignableStaff(role: AssignableStaff['role'], enabled = true, branchId?: number) {
  const query = useQuery({
    queryKey: ['staff', 'assignable', role, branchId ?? null],
    queryFn: () => identityApi.listStaff({ role, status: 'ACTIVE', size: 100, ...(branchId !== undefined && { branchId }) }),
    select: (page) => assignableOf(role, page),
    enabled,
    staleTime: 30_000,
    ...retry,
  });
  const staffPickerBlocked = query.isError && toApiError(query.error).statusCode === 403;
  return {
    staff: query.data ?? [],
    staffPickerBlocked,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

// ─── Thông điệp lỗi ───

/** Thao tác của `visitErrorMessage`: các thao tác có phân quyền (`VisitAction`) cộng xóa mũi tiêm, có quyền như `vaccinate` nhưng BE trả thêm 409 riêng (Care Task trỏ tới mũi). */
export type VisitErrorAction = VisitAction | 'deleteVaccination';

/**
 * 409 của lượt theo thao tác khám (T37): lượt không còn ở trạng thái cho phép thao tác. Câu mặc định "đã tải lại hàng đợi" không đúng ở đây vì VET đang mở đúng lượt đó
 * và cần biết phải làm gì tiếp (bệnh án đã khóa → bản bổ sung; chưa gọi → gọi lượt; chưa hoàn tất → sửa trực tiếp). `from` là trạng thái cũ BE nêu trong thông điệp.
 */
function examConflictMessage(action: VisitErrorAction | undefined, from: string | undefined): string | null {
  switch (action) {
    case 'writeRecord':
      if (from === 'COMPLETED') return 'Lượt đã hoàn tất nên bệnh án đã khóa. Muốn bổ sung hãy dùng "Thêm bổ sung".';
      return from === 'CANCELLED' ? 'Lượt đã hủy nên không ghi được.' : 'Lượt chưa được gọi vào khám nên chưa ghi được. Gọi lượt trước rồi ghi.';
    case 'vaccinate':
    case 'deleteVaccination':
      if (from === 'COMPLETED') return 'Lượt đã hoàn tất nên không ghi hay xóa mũi tiêm được. Sai sót ghi bằng bản bổ sung bệnh án.';
      return from === 'CANCELLED' ? 'Lượt đã hủy nên không ghi mũi tiêm được.' : 'Lượt chưa được gọi vào khám nên chưa ghi mũi tiêm được.';
    case 'prescribe':
      if (from === 'COMPLETED') return 'Lượt đã hoàn tất nên đơn thuốc đã khóa cùng bệnh án, không kê hay xóa thuốc được. Sai sót ghi bằng bản bổ sung bệnh án.';
      return from === 'CANCELLED' ? 'Lượt đã hủy nên không kê đơn được.' : 'Lượt chưa được gọi vào khám nên chưa kê đơn được.';
    case 'addendum':
      return 'Lượt chưa hoàn tất nên chưa thêm bản bổ sung được. Hãy sửa trực tiếp bệnh án.';
    case 'complete':
      return from === 'COMPLETED' ? 'Lượt đã được hoàn tất rồi.' : 'Lượt chưa ở trạng thái đang khám (đang chờ hoặc đã hủy) nên chưa hoàn tất được.';
    default:
      return null;
  }
}

/**
 * Câu báo lỗi cho quầy tiếp nhận và bàn khám. 409 tách theo thực thể BE nêu trong thông điệp: "lịch hẹn" → tiếp nhận như khách vãng lai; "hạng ưu tiên" → đã là cấp cứu; còn lại
 * (lượt) → theo thao tác khám (`examConflictMessage`) hoặc đã đổi ở nơi khác. 403 ACCESS_DENIED_SCOPE_MISMATCH tùy thao tác: gọi lượt = chưa được gán cho mình; ghi bệnh án,
 * hoàn tất, tiêm chủng (ghi và xóa mũi), kê đơn = không phải người phụ trách HIỆN TẠI (BE không xét người đã ghi mũi, ADR-0075). Thú đã mất báo mã BR-TN-01 (không phải BR-KH-05).
 * 409 CONCURRENCY_CONFLICT của xóa mũi tiêm là mũi còn Care Task nhắc tái chủng trỏ tới (FK RESTRICT), không phải lượt vừa được gán lại.
 */
export function visitErrorMessage(e: unknown, action?: VisitErrorAction): string {
  const error = toApiError(e);
  switch (error.errorCode) {
    case 'INVALID_STATE_TRANSITION': {
      const from = /từ (\w+) sang/.exec(error.message)?.[1];
      if (error.message.includes('lịch hẹn')) {
        // CHECKED_IN: đã có lượt (danh sách chờ cũ hoặc quầy khác vừa tiếp nhận); tiếp nhận thêm sẽ tạo lượt và Order thứ hai vì BE không chặn thú đã có lượt mở.
        if (from === 'CHECKED_IN') return 'Lịch hẹn này đã được tiếp nhận rồi, lượt đang ở trong hàng đợi. Hãy xem hàng đợi, đừng tiếp nhận thêm.';
        if (from === 'NO_SHOW') return 'Lịch hẹn đã quá giờ nên tính là không đến. Nếu khách vẫn đến, hãy tiếp nhận như khách vãng lai (không chọn lịch hẹn).';
        return 'Lịch hẹn này đã hủy hoặc đã hoàn tất nên không tiếp nhận được.';
      }
      if (error.message.includes('hạng ưu tiên')) return 'Lượt này đã là ca cấp cứu.';
      const exam = examConflictMessage(action, from);
      if (exam) return exam;
      // BR-TN-07: lượt đã gọi không hủy được; nhân viên phụ trách hoàn tất theo BR-KB-02. (BR-TN-03, khách trễ, BE không báo thành lỗi: chỉ đổi hạng ưu tiên.)
      if (from === 'IN_PROGRESS' && error.message.includes('sang CANCELLED')) {
        return 'Lượt đã được gọi nên không hủy được. Nhân viên phụ trách phải hoàn tất lượt (BR-KB-02).';
      }
      return 'Lượt vừa được gọi, hủy hoặc thay đổi ở nơi khác nên không làm được thao tác này. Đã tải lại hàng đợi.';
    }
    case 'CONCURRENCY_CONFLICT':
      // ASSUMPTION: BE trả 409 chung chung (GlobalExceptionHandler.handleDataConflict, không nêu nguyên nhân). Khi xóa mũi, lượt và mũi đã bị khóa nên nguyên nhân còn lại là FK care_tasks.vaccination_id.
      if (action === 'deleteVaccination') return 'Mũi này đã gắn nhắc tái chủng nên không xóa được.';
      return 'Lượt vừa được gán lại bởi thao tác khác. Đã tải lại hàng đợi.';
    case 'ACCESS_DENIED_SCOPE_MISMATCH':
      if (action === 'call') return 'Lượt này không được gán cho bạn nên bạn không gọi được.';
      if (action === 'reassign') return 'Chỉ quản lý chi nhánh của lượt mới gán lại được. Quản lý chuỗi chỉ gán lại khi chi nhánh không còn quản lý hoạt động.';
      if (action === 'writeRecord') return 'Chỉ bác sĩ được gán lượt này mới ghi được bệnh án và cân nặng.';
      if (action === 'complete') return 'Chỉ người được gán lượt mới hoàn tất được.';
      if (action === 'vaccinate' || action === 'deleteVaccination') return 'Chỉ bác sĩ đang được gán lượt này mới ghi hoặc xóa được mũi tiêm.';
      if (action === 'prescribe') return 'Chỉ bác sĩ được gán lượt này mới kê đơn hoặc xóa thuốc được.';
      return 'Lượt này thuộc chi nhánh khác nên bạn không thao tác được.';
    case 'ACCESS_DENIED':
      return 'Bạn không có quyền thực hiện thao tác này.';
    case 'RESOURCE_NOT_FOUND':
      return plainMessage(error);
    default:
      break;
  }
  if (error.ruleId === 'BR-TN-08') {
    return 'Người phụ trách hiện tại không còn bị khóa hay đổi chức vụ nên chưa gán lại được. Chỉ gán lại lượt đã gọi khi người phụ trách không còn đủ điều kiện.';
  }
  return plainMessage(error);
}
