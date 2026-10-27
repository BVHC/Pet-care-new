// Truy vấn riêng của trang chi tiết lưu trú: nhật ký tự làm mới khi thú còn trong chuồng (UC60).
// Cùng khóa ['boarding', 'care-logs', id] với `useCareLogs` nên mọi lệnh ghi / `useRefreshBoarding()` vẫn làm mới được;
// viết lại vì `useCareLogs` không nhận tùy chọn `refetchInterval`.
import { useQuery } from '@tanstack/react-query';
import { toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { retryDelay, shouldRetry } from '@/shared/constants/realtime';
import type { Booking } from '@/shared/types/boarding';
import { isInStay } from '@/shared/utils/boarding';
import { hasCareLogs } from './model';

export const CARE_LOG_REFRESH_MS = 60_000;

export function useStayCareLogs(booking: Pick<Booking, 'bookingId' | 'status'>) {
  return useQuery({
    queryKey: ['boarding', 'care-logs', booking.bookingId],
    queryFn: () => boardingApi.listCareLogs(booking.bookingId),
    // BOOKED / CANCELLED / NO_SHOW chưa có nhật ký: không gọi API.
    enabled: hasCareLogs(booking.status),
    // Chỉ khi thú còn trong chuồng, và React Query bỏ qua nhịp khi tab đang ẩn; rời trang thì observer tự dọn timer.
    refetchInterval: isInStay(booking.status) ? CARE_LOG_REFRESH_MS : false,
    refetchIntervalInBackground: false,
    retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)),
    retryDelay,
  });
}
