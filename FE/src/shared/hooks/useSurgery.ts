// React Query cho phẫu thuật (UC50; surgery-v1 #1–8): khóa gốc ['surgery', …]. Lệnh ghi làm mới ['surgery'] cùng các gốc nó chạm tới — ['visits'] (hoàn tất lượt bị
// chặn / mở khi phẫu thuật xong), ['sales'] (hoàn tất thêm dịch vụ vào Order), ['notifications'] (lập kế hoạch báo lễ tân) — trong `onSettled`, kể cả khi BE từ chối:
// 409 / 400 nghĩa là phẫu thuật hoặc lượt đã đổi ở nơi khác, màn đang mở không được giữ số cũ.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { surgeryApi } from '../api/surgery.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { CancelSurgeryRequest, CompleteSurgeryRequest, ConsentSurgeryRequest, PlanSurgeryRequest, SurgeryQuery } from '../types/surgery';

export const SURGERY_KEY = ['surgery'] as const;
const TOUCHED = [SURGERY_KEY, ['visits'], ['sales'], ['notifications']] as const;

/** Lỗi 4xx (403 phạm vi, 404) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #2. Chưa chọn lượt (null) thì chưa gọi. */
export const useVisitSurgeries = (visitId: number | null, enabled = true) =>
  useQuery({ queryKey: ['surgery', 'visit', visitId], queryFn: () => surgeryApi.listByVisit(visitId!), enabled: enabled && visitId != null, ...retry });

/** #3 (lễ tân, VET). */
export const useSurgeries = (query: SurgeryQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['surgery', 'list', query], queryFn: () => surgeryApi.list(query), enabled, placeholderData: keepPreviousData, ...retry });

/** #4. */
export const useSurgery = (surgeryId: number | null, enabled = true) =>
  useQuery({ queryKey: ['surgery', 'detail', surgeryId], queryFn: () => surgeryApi.getSurgery(surgeryId!), enabled: enabled && surgeryId != null, ...retry });

export function useSurgeryMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => Promise.all(TOUCHED.map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

/** #1 (VET phụ trách). */
export const usePlanSurgery = () => useSurgeryMutation(({ visitId, ...body }: { visitId: number } & PlanSurgeryRequest) => surgeryApi.plan(visitId, body));
/** #5 (lễ tân). */
export const useConsentSurgery = () => useSurgeryMutation(({ surgeryId, ...body }: { surgeryId: number } & ConsentSurgeryRequest) => surgeryApi.consent(surgeryId, body));
/** #6 (VET phụ trách). */
export const useStartSurgery = () => useSurgeryMutation((surgeryId: number) => surgeryApi.start(surgeryId));
/** #7 (VET phụ trách). */
export const useCompleteSurgery = () => useSurgeryMutation(({ surgeryId, ...body }: { surgeryId: number } & CompleteSurgeryRequest) => surgeryApi.complete(surgeryId, body));
/** #8 (VET phụ trách; lễ tân khi chưa bắt đầu). */
export const useCancelSurgery = () => useSurgeryMutation(({ surgeryId, ...body }: { surgeryId: number } & CancelSurgeryRequest) => surgeryApi.cancel(surgeryId, body));
