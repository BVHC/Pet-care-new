// Hook riêng của bốn hộp thoại nhận / trả thú (T48 UC59). Không sửa useBoarding.ts / useSales.ts: thêm ở đây.
import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { salesApi } from '@/shared/api/sales.api';
import { retryDelay, shouldRetry } from '@/shared/constants/realtime';
import { BOARDING_KEY } from '@/shared/hooks/useBoarding';
import { useAccount } from '@/shared/stores/session.store';
import type { Role } from '@/shared/types/auth';

/** Lỗi 4xx là kết luận, không thử lại (404 / 409 / 400 không đổi khi hỏi lại). */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/**
 * Trả thú phụ thuộc việc thu tiền xảy ra Ở MÀN KHÁC (bàn Lễ tân), nên không dùng lại dữ liệu cũ trong cache:
 * luôn hỏi lại khi mở, và khóa cache không giữ sau khi đóng (gcTime 0). Khung làm mới khi lấy lại tiêu điểm là `useRefreshOnFocus`
 * (React Query v5 chỉ nghe `visibilitychange`, không nghe `focus` của cửa sổ).
 */
const fresh = { staleTime: 0, gcTime: 0, refetchOnMount: 'always', refetchOnWindowFocus: false } as const;

/** Đặt chỗ mới nhất (chứa `activeOrderId`, A10). Khóa dưới ['boarding'] nên mọi lệnh lưu trú làm mới nó. */
export const useStayBooking = (bookingId: number) =>
  useQuery({ queryKey: ['boarding', 'stay-booking', bookingId], queryFn: () => boardingApi.get(bookingId), ...fresh, ...retry });

/** Order lưu trú đọc đầy đủ (có dòng → số đêm). Khóa dưới ['sales'] nên thu tiền / hủy phiên làm mới nó. */
export const useStayOrder = (orderId: number | null) =>
  useQuery({
    queryKey: ['sales', 'stay-order', orderId],
    queryFn: () => salesApi.getOrder(orderId!),
    enabled: orderId != null,
    ...fresh,
    ...retry,
  });

/** Làm mới sau khi một lệnh bị từ chối vì dữ liệu đã đổi (409), hoặc để lấy trạng thái Order sau khi thu tiền ở màn khác. */
export function useRefreshStay() {
  const queryClient = useQueryClient();
  return () => Promise.all([queryClient.invalidateQueries({ queryKey: BOARDING_KEY }), queryClient.invalidateQueries({ queryKey: ['sales'] })]);
}

/** Gọi `refresh` mỗi khi cửa sổ lấy lại tiêu điểm hoặc tab hiện lại (lễ tân thu tiền ở cửa sổ khác rồi quay về). */
export function useRefreshOnFocus(refresh: () => unknown) {
  const latest = useRef(refresh);
  latest.current = refresh;
  useEffect(() => {
    const onFocus = () => {
      if (document.visibilityState !== 'hidden') latest.current();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, []);
}

/** Vai trò hiện tại có nằm trong danh sách được phép không (hộp thoại tự chặn, không gọi API cấm → không 403). */
export function useAllowed(roles: readonly Role[]): boolean {
  const role = useAccount()?.role;
  return !!role && roles.includes(role);
}
