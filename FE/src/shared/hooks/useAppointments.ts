// React Query cho lịch hẹn (UC39, UC40): khóa gốc ['appointments']; lệnh ghi làm mới cả gốc vì đặt / đổi / hủy
// đổi cùng lúc danh sách, chi tiết, khung giờ trống (quota) và hạn chế đặt online (hủy muộn, BR-LH-09).
// Dữ liệu công khai cho trang đặt lịch (chi nhánh, dịch vụ) dùng khóa ['public', …].
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { appointmentApi } from '../api/appointment.api';
import { branchApi } from '../api/branch.api';
import { catalogApi } from '../api/catalog.api';
import { toApiError } from '../api/api-error';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { AppointmentQuery } from '../types/appointment';

/** Lỗi 4xx không đổi khi thử lại (lỗi axios đổi sang ApiError để đọc mã HTTP). */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

export const APPOINTMENTS_KEY = ['appointments'] as const;

/** Chưa đủ chi nhánh, dịch vụ, ngày thì chưa gọi. */
export const useAvailableSlots = (branchId: number | null, serviceId: number | null, date: string | null) =>
  useQuery({
    queryKey: ['appointments', 'slots', branchId, serviceId, date],
    queryFn: () => appointmentApi.listAvailableSlots(branchId!, serviceId!, date!),
    enabled: branchId != null && serviceId != null && !!date,
    ...retry,
  });

export const useAppointmentList = (query: AppointmentQuery, enabled = true) =>
  useQuery({
    queryKey: ['appointments', 'list', query],
    queryFn: () => appointmentApi.list(query),
    enabled,
    placeholderData: keepPreviousData,
    ...retry,
  });

export const useAppointment = (appointmentId: number | null) =>
  useQuery({
    queryKey: ['appointments', 'detail', appointmentId],
    queryFn: () => appointmentApi.get(appointmentId!),
    enabled: appointmentId != null,
    ...retry,
  });

export const useMyBookingRestriction = () =>
  useQuery({ queryKey: ['appointments', 'restriction', 'me'], queryFn: appointmentApi.myRestriction, ...retry });

export const useCustomerBookingRestriction = (customerId: number | null) =>
  useQuery({
    queryKey: ['appointments', 'restriction', customerId],
    queryFn: () => appointmentApi.customerRestriction(customerId!),
    enabled: customerId != null,
    ...retry,
  });

/** Hook chỉ gọi API và làm mới cache; màn hình tự báo thành công / lỗi. */
export function useAppointmentMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => queryClient.invalidateQueries({ queryKey: APPOINTMENTS_KEY }) });
}

/**
 * Làm mới cả gốc sau khi đặt / đổi bị từ chối: khung giờ (BR-LH-03 vừa hết chỗ, BR-LH-04 vừa quá hạn) và hạn chế
 * đặt online (BR-LH-09 vừa phát sinh — trang đặt lịch ẩn form theo GET /me/booking-restriction).
 */
export function useRefreshAppointments() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: APPOINTMENTS_KEY });
}

export const usePublicBranches = () =>
  useQuery({ queryKey: ['public', 'branches'], queryFn: () => branchApi.listPublicBranches(), ...retry });

export const usePublicServices = (branchId: number | null) =>
  useQuery({
    queryKey: ['public', 'services', branchId],
    queryFn: () => catalogApi.listPublicServices({ branchId: branchId! }),
    enabled: branchId != null,
    ...retry,
  });
