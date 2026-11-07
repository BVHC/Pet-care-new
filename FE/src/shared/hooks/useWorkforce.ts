// React Query cho nhân sự (UC34–38): khóa gốc ['workforce']. Mọi lệnh ghi làm mới cả gốc (kể cả khi BE từ chối 4xx), vì phân ca, lịch của tôi, yêu cầu chờ duyệt
// và `pendingRequestId` của phân ca đổi cùng lúc: duyệt / hủy / vắng đổi trạng thái phân ca và yêu cầu PENDING của nó, nên 400 / 409 thường nghĩa là người khác đã xử lý trước.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { workforceApi } from '../api/workforce.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { MyShiftAssignmentQuery, ShiftAssignmentQuery, ShiftDefinitionQuery, ShiftRequestQuery } from '../types/workforce';

/** Lỗi 4xx (400 khoảng ngày, 403 vai trò) không đổi khi thử lại; 5xx và mất mạng thử tối đa 3 lần. */
const opts = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;
const paged = { ...opts, placeholderData: keepPreviousData } as const;

/** #1 (BRANCH_MANAGER). */
export const useShiftDefinitions = (query: ShiftDefinitionQuery = {}) =>
  useQuery({ queryKey: ['workforce', 'definitions', query], queryFn: () => workforceApi.listShiftDefinitions(query), ...opts });

/** #4 (BRANCH_MANAGER): `from`, `to` bắt buộc. */
export const useShiftAssignments = (query: ShiftAssignmentQuery, enabled = true) =>
  useQuery({ queryKey: ['workforce', 'assignments', query], queryFn: () => workforceApi.listShiftAssignments(query), enabled, ...paged });

/** #8 (A06–A08). */
export const useMyShiftAssignments = (query: MyShiftAssignmentQuery, enabled = true) =>
  useQuery({ queryKey: ['workforce', 'my-assignments', query], queryFn: () => workforceApi.listMyShiftAssignments(query), enabled, ...paged });

/** #10 (A06–A08). */
export const useMyShiftRequests = (query: ShiftRequestQuery = {}) =>
  useQuery({ queryKey: ['workforce', 'my-requests', query], queryFn: () => workforceApi.listMyShiftRequests(query), ...paged });

/** #12 (BRANCH_MANAGER): `status` vắng = PENDING. */
export const useShiftRequests = (query: ShiftRequestQuery = {}) =>
  useQuery({ queryKey: ['workforce', 'requests', query], queryFn: () => workforceApi.listShiftRequests(query), ...paged });

/**
 * Lệnh ghi #2, #3, #5, #6, #7, #9, #11, #13, #14: `useWorkforceMutation(workforceApi.approveShiftRequest)`.
 * Hàm nhiều tham số thì bọc lại: `({ id, body }) => workforceApi.xxx(id, body)` (mutationFn nhận đúng một biến).
 */
export function useWorkforceMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => queryClient.invalidateQueries({ queryKey: ['workforce'] }) });
}
