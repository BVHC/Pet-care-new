// React Query cho nội trú (UC55, UC56; inpatient-v1 #1–10): khóa gốc ['inpatient', …]. Lệnh ghi làm mới ['inpatient'] cùng các gốc nó chạm tới — ['visits'] (nhập viện từ lưu trú tạo Visit điều trị,
// xuất viện mở đường hoàn tất lượt), ['sales'] (xuất viện thêm dòng dịch vụ vào Order), ['boarding'] (chuồng, đặt chỗ lưu trú), ['pets'] (cân nặng ghi từ theo dõi), ['notifications'] (theo dõi
// bất thường báo VET và khách) — trong `onSettled`, kể cả khi BE từ chối: 400 / 409 nghĩa là ca hoặc y lệnh đã đổi ở nơi khác, màn đang mở không được giữ số cũ.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toApiError } from '../api/api-error';
import { inpatientApi } from '../api/inpatient.api';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type {
  AdmissionOrderQuery,
  AdmissionQuery,
  AdmitRequest,
  CompleteAdmissionOrderRequest,
  CreateAdmissionOrderRequest,
  CreateObservationRequest,
  DischargeRequest,
  ObservationQuery,
  ReasonRequest,
} from '../types/inpatient';

export const INPATIENT_KEY = ['inpatient'] as const;
const TOUCHED = [INPATIENT_KEY, ['visits'], ['sales'], ['boarding'], ['pets'], ['notifications']] as const;

/** Lỗi 4xx (403 phạm vi, 404) không đổi khi thử lại. */
const retry = { retry: (count: number, error: unknown) => shouldRetry(count, toApiError(error)), retryDelay } as const;

/** #2 (lễ tân xem, VET, CARETAKER): `status` vắng = ADMITTED. */
export const useAdmissions = (query: AdmissionQuery = {}, enabled = true) =>
  useQuery({ queryKey: ['inpatient', 'list', query], queryFn: () => inpatientApi.list(query), enabled, placeholderData: keepPreviousData, ...retry });

/** #3. Chưa chọn ca (null) thì chưa gọi. */
export const useAdmission = (admissionId: number | null, enabled = true) =>
  useQuery({ queryKey: ['inpatient', 'detail', admissionId], queryFn: () => inpatientApi.getAdmission(admissionId!), enabled: enabled && admissionId != null, ...retry });

/** #6. */
export const useAdmissionOrders = (admissionId: number | null, query: AdmissionOrderQuery = {}, enabled = true) =>
  useQuery({
    queryKey: ['inpatient', 'orders', admissionId, query],
    queryFn: () => inpatientApi.listOrders(admissionId!, query),
    enabled: enabled && admissionId != null,
    ...retry,
  });

/** #10. */
export const useObservations = (admissionId: number | null, query: ObservationQuery = {}, enabled = true) =>
  useQuery({
    queryKey: ['inpatient', 'observations', admissionId, query],
    queryFn: () => inpatientApi.listObservations(admissionId!, query),
    enabled: enabled && admissionId != null,
    placeholderData: keepPreviousData,
    ...retry,
  });

export function useInpatientMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => Promise.all(TOUCHED.map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

/** #1 (VET). */
export const useAdmit = () => useInpatientMutation((body: AdmitRequest) => inpatientApi.admit(body));
/** #4 (VET). */
export const useDischarge = () => useInpatientMutation(({ admissionId, ...body }: { admissionId: number } & DischargeRequest) => inpatientApi.discharge(admissionId, body));
/** #5 (VET). */
export const useCreateAdmissionOrder = () =>
  useInpatientMutation(({ admissionId, ...body }: { admissionId: number } & CreateAdmissionOrderRequest) => inpatientApi.createOrder(admissionId, body));
/** #7 (VET; CARETAKER với việc không phải thuốc). */
export const useCompleteAdmissionOrder = () =>
  useInpatientMutation(({ admissionOrderId, ...body }: { admissionOrderId: number } & CompleteAdmissionOrderRequest) => inpatientApi.completeOrder(admissionOrderId, body));
/** #8 (VET). */
export const useCancelAdmissionOrder = () =>
  useInpatientMutation(({ admissionOrderId, ...body }: { admissionOrderId: number } & ReasonRequest) => inpatientApi.cancelOrder(admissionOrderId, body));
/** #9 (VET, CARETAKER). */
export const useCreateObservation = () =>
  useInpatientMutation(({ admissionId, ...body }: { admissionId: number } & CreateObservationRequest) => inpatientApi.createObservation(admissionId, body));
