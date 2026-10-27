// React Query cho lưu trú (UC54, UC57–UC60): khóa gốc ['boarding']. Lệnh ghi làm mới gốc này và các gốc bị chạm theo
// chuỗi hệ quả (Order trả thú → ['sales']; nhận thú ghi cân nặng / chặn đánh dấu mất → ['pets']; nhật ký bất thường,
// hủy do phòng khám → ['notifications']; hủy muộn tính vào hạn chế đặt online → ['appointments']).
// Loại chuồng và giá lấy ở catalog / dữ liệu công khai (khóa ['public', …]), không ở đây.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { boardingApi } from '../api/boarding.api';
import { toApiError } from '../api/api-error';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { AvailabilityQuery, BookingQuery, KennelQuery } from '../types/boarding';

/** Lỗi 4xx không đổi khi thử lại (lỗi axios đổi sang ApiError để đọc mã HTTP). */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

export const BOARDING_KEY = ['boarding'] as const;
/** Các gốc khác mà một lệnh lưu trú có thể làm đổi. */
const TOUCHED_KEYS = [['sales'], ['pets'], ['notifications'], ['appointments']] as const;

/** VET bị 403 ở GET /kennels: chỉ gọi khi vai trò được phép (`enabled`). */
export const useKennels = (query: KennelQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['boarding', 'kennels', query], queryFn: () => boardingApi.listKennels(query), enabled, ...retry });

/** Chưa đủ chi nhánh, loại chuồng, ngày thì chưa gọi. */
export const useBoardingAvailability = (branchId: number | null, query: Partial<AvailabilityQuery>) =>
  useQuery({
    queryKey: ['boarding', 'availability', branchId, query],
    queryFn: () => boardingApi.availability(branchId!, query as AvailabilityQuery),
    enabled: branchId != null && query.kennelTypeId != null && !!query.checkInDate && !!query.checkOutDate,
    ...retry,
  });

export const useBookingList = (query: BookingQuery = {}, enabled = true) =>
  useQuery({
    queryKey: ['boarding', 'bookings', query],
    queryFn: () => boardingApi.list(query),
    enabled,
    placeholderData: keepPreviousData,
    ...retry,
  });

export const useBooking = (bookingId: number | null) =>
  useQuery({
    queryKey: ['boarding', 'booking', bookingId],
    queryFn: () => boardingApi.get(bookingId!),
    enabled: bookingId != null,
    ...retry,
  });

/** 409 khi đặt chỗ không còn `BOOKED`. Không lưu lâu: số liệu đổi theo kho vaccine / chuồng. */
export const useCheckInReadiness = (bookingId: number | null) =>
  useQuery({
    queryKey: ['boarding', 'readiness', bookingId],
    queryFn: () => boardingApi.checkInReadiness(bookingId!),
    enabled: bookingId != null,
    staleTime: 0,
    gcTime: 0,
    ...retry,
  });

export const useCareLogs = (bookingId: number | null, enabled = true) =>
  useQuery({
    queryKey: ['boarding', 'care-logs', bookingId],
    queryFn: () => boardingApi.listCareLogs(bookingId!),
    enabled: enabled && bookingId != null,
    ...retry,
  });

/** Hook chỉ gọi API và làm mới cache; màn hình tự báo thành công / lỗi. */
export function useBoardingMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: BOARDING_KEY }),
        ...TOUCHED_KEYS.map((queryKey) => queryClient.invalidateQueries({ queryKey })),
      ]),
  });
}

/** Làm mới sau khi một lệnh bị từ chối vì dữ liệu đã đổi (409: người khác vừa nhận / hủy / giao thú). */
export function useRefreshBoarding() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: BOARDING_KEY });
}
