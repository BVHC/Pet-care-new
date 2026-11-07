// React Query cho vòng đời chi nhánh (UC13, branchlife-v1; chỉ SUPER_MANAGER). Khóa gốc ['branchlife', …].
// Lệnh ghi làm mới ['branchlife'] (số tồn đọng đổi), ['branch'] (trạng thái chi nhánh ở màn quản lý chi nhánh) và ['public'] (trang công khai chỉ hiện chi nhánh
// ACTIVE, staleTime 5 phút, dùng chung QueryClient). Làm mới cả khi BE từ chối: 400 BR-CN-08 / BR-CN-09 và 409 nghĩa là tồn đọng hoặc trạng thái đã đổi sau
// lúc mở form.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { branchlifeApi } from '../api/branchlife.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import { QUERY_KEYS } from '../constants/queryKeys';

const ROOT = ['branchlife'] as const;

/** Lỗi 4xx (403, 404) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #1; `null` = chưa chọn chi nhánh thì chưa gọi. Số liệu tính tại lúc xem nên không tự hỏi lại khi focus. */
export const useClosureImpact = (branchId: number | null) =>
  useQuery({
    queryKey: [...ROOT, 'closure-impact', branchId],
    queryFn: () => branchlifeApi.closureImpact(branchId!),
    enabled: branchId != null,
    refetchOnWindowFocus: false,
    ...retry,
  });

/** Một hook cho cả #2–4: màn hình truyền hàm gọi API tương ứng, vd `useBranchLifeMutation((reason: string) => branchlifeApi.suspend(id, { reason, cancelBookings }))`. */
export function useBranchLifeMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ROOT }),
        queryClient.invalidateQueries({ queryKey: ['branch'] }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.public.all }),
      ]),
  });
}
