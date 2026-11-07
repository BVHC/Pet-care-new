// React Query cho khám tại nhà (UC43, UC51; homevisit-v1 #1–6): khóa gốc ['homevisit', …]. Lệnh ghi làm mới ['homevisit'] cùng các gốc nó chạm tới — ['appointments'] (đặt lịch chiếm quota khung giờ,
// không gặp được khách vào hạn chế đặt online), ['visits'] và ['sales'] (bắt đầu khám tạo Visit và Order), ['notifications'] (không gặp được khách báo khách) — trong `onSettled`, kể cả khi BE từ chối:
// 409 / 400 nghĩa là khung hết chỗ hoặc lịch đã đổi ở nơi khác, màn đang mở không được giữ số cũ.
// Hủy / đổi giờ lịch tại nhà dùng `useAppointments` (appointment-v1, BR-LH-14), chỉ làm mới ['appointments']: màn tại nhà gọi thêm `useInvalidateHomeVisits()` sau khi hủy / đổi.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { homevisitApi } from '../api/homevisit.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { CreateHomeVisitAppointmentRequest, HomeVisitQuery, MarkUnreachableRequest, MyHomeVisitQuery } from '../types/homevisit';

export const HOMEVISIT_KEY = ['homevisit'] as const;
const TOUCHED = [HOMEVISIT_KEY, ['appointments'], ['visits'], ['sales'], ['notifications']] as const;

/** Lỗi 4xx (403 phạm vi, 404) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #2 (khách). */
export const useMyHomeVisits = (query: MyHomeVisitQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['homevisit', 'mine', query], queryFn: () => homevisitApi.listMine(query), enabled, placeholderData: keepPreviousData, ...retry });

/** #3 (lễ tân, VET): `date` vắng = hôm nay. */
export const useHomeVisits = (query: HomeVisitQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['homevisit', 'list', query], queryFn: () => homevisitApi.list(query), enabled, placeholderData: keepPreviousData, ...retry });

/** #4. Chưa chọn lịch (null) thì chưa gọi. */
export const useHomeVisit = (appointmentId: number | null, enabled = true) =>
  useQuery({ queryKey: ['homevisit', 'detail', appointmentId], queryFn: () => homevisitApi.getAppointment(appointmentId!), enabled: enabled && appointmentId != null, ...retry });

export function useHomeVisitMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => Promise.all(TOUCHED.map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

/** Làm mới ['homevisit'] sau khi hủy / đổi giờ bằng hook của appointment-v1. */
export function useInvalidateHomeVisits() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: HOMEVISIT_KEY });
}

/** #1 (khách; lễ tân đặt hộ). */
export const useCreateHomeVisit = () => useHomeVisitMutation((body: CreateHomeVisitAppointmentRequest) => homevisitApi.create(body));
/** #5 (VET của chi nhánh). */
export const useStartHomeVisit = () => useHomeVisitMutation((appointmentId: number) => homevisitApi.start(appointmentId));
/** #6 (VET của chi nhánh). */
export const useMarkHomeVisitUnreachable = () =>
  useHomeVisitMutation(({ appointmentId, ...body }: { appointmentId: number } & MarkUnreachableRequest) => homevisitApi.markUnreachable(appointmentId, body));
