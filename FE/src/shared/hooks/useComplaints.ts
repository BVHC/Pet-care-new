// React Query cho khiếu nại (UC85, UC86): khóa gốc ['complaint']. Mọi lệnh ghi làm mới cả gốc (kể cả khi BE từ chối 4xx): mỗi lệnh đổi trạng thái, tin nhắn và
// `lastActivityAt` của khiếu nại, còn 409 nghĩa là bên kia (khách hoặc người xử lý) đã đổi trạng thái trước, nên danh sách và chi tiết đang mở đã cũ.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { complaintApi } from '../api/complaint.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { ComplaintQuery, MyComplaintQuery } from '../types/complaint';

/** Lỗi 4xx (403 phạm vi, 404 của người khác) không đổi khi thử lại; 5xx và mất mạng thử tối đa 3 lần. */
const opts = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #2 (CUSTOMER). */
export const useMyComplaints = (query: MyComplaintQuery = {}) =>
  useQuery({ queryKey: ['complaint', 'mine', query], queryFn: () => complaintApi.listMyComplaints(query), placeholderData: keepPreviousData, ...opts });

/** #3 (CUSTOMER): của người khác 404. */
export const useMyComplaint = (complaintId: number | null) =>
  useQuery({ queryKey: ['complaint', 'mine-detail', complaintId], queryFn: () => complaintApi.getMyComplaint(complaintId!), enabled: complaintId != null, ...opts });

/** #8 (SUPER_MANAGER, BRANCH_MANAGER, RECEPTIONIST): `status` vắng = OPEN + IN_PROGRESS. */
export const useComplaints = (query: ComplaintQuery = {}) =>
  useQuery({ queryKey: ['complaint', 'list', query], queryFn: () => complaintApi.listComplaints(query), placeholderData: keepPreviousData, ...opts });

/** #9. */
export const useComplaint = (complaintId: number | null) =>
  useQuery({ queryKey: ['complaint', 'detail', complaintId], queryFn: () => complaintApi.getComplaint(complaintId!), enabled: complaintId != null, ...opts });

/**
 * Lệnh ghi #1, #4–7, #10–12: `useComplaintMutation(complaintApi.takeComplaint)`.
 * Hàm nhiều tham số thì bọc lại: `({ id, body }) => complaintApi.xxx(id, body)` (mutationFn nhận đúng một biến).
 */
export function useComplaintMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSettled: () => queryClient.invalidateQueries({ queryKey: ['complaint'] }) });
}
