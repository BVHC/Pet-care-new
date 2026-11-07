// React Query cho gộp hồ sơ khách trùng (UC27, custmerge-v1; chỉ BRANCH_MANAGER). Khóa gốc ['custmerge', …].
// Gộp không hoàn tác và đổi chủ của thú, địa chỉ, feedback…: lệnh ghi làm mới ['custmerge'] (xem trước), ['counter-customers'] (tra cứu / chi tiết khách ở quầy),
// ['customers'] và ['pets']. Làm mới cả khi BE từ chối: BR-KH-11 (hồ sơ vừa có Visit / Order mới, hoặc đã bị gộp ở nơi khác) và 409 CONCURRENCY_CONFLICT nghĩa là dữ liệu đã đổi
// sau lúc xem trước. Danh sách lịch hẹn / đơn của khách (module khác) do màn hình tự làm mới nếu đang mở.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { custmergeApi } from '../api/custmerge.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { QUERY_KEYS } from '../constants/queryKeys';
import type { CustomerMergeRequest } from '../types/custmerge';

const ROOT = ['custmerge'] as const;

/** Lỗi 4xx (400 nguồn trùng đích, 403, 404) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/**
 * #1; chưa chọn đủ hai hồ sơ (hoặc chọn trùng nhau) thì chưa gọi. Số liệu tính tại lúc xem, và gộp xong trạng thái hai hồ sơ đổi nên không giữ cache
 * quá lúc đang mở (`gcTime: 0`) và không tự hỏi lại khi focus.
 */
export const useMergePreview = (targetCustomerId: number | null, sourceCustomerId: number | null) =>
  useQuery({
    queryKey: [...ROOT, 'preview', targetCustomerId, sourceCustomerId],
    queryFn: () => custmergeApi.preview(targetCustomerId!, sourceCustomerId!),
    enabled: targetCustomerId != null && sourceCustomerId != null && targetCustomerId !== sourceCustomerId,
    refetchOnWindowFocus: false,
    gcTime: 0,
    ...retry,
  });

/** #2; `confirm: true` do màn hình gửi sau khi người dùng đã xem #1 (BR-KH-14). */
export function useMergeCustomers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CustomerMergeRequest) => custmergeApi.merge(body),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ROOT }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.counterCustomers.all }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.customers.all }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.pets.all }),
      ]),
  });
}
