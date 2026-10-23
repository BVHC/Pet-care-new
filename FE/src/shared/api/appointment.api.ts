// Lịch hẹn (UC39, UC40) theo docs/api/appointment-v1.md #1–9.
// Khung giờ, đặt / đổi / hủy: CUSTOMER (lịch của mình), RECEPTIONIST (chi nhánh mình); xem: thêm BRANCH_MANAGER
// (chi tiết thêm VET, CARETAKER); hạn chế đặt online: khách xem của mình, lễ tân / quản lý chi nhánh xem và gỡ toàn chuỗi.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Appointment,
  AppointmentQuery,
  BookAppointmentRequest,
  CancelAppointmentRequest,
  LiftRestrictionRequest,
  RescheduleRequest,
  RestrictionStatus,
  Slot,
} from '../types/appointment';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const appointmentApi = {
  /** Ngày nghỉ / ngoài khoảng đặt được trả mảng rỗng (A1); khung AVAILABLE chưa giữ chỗ (ADR-0046). */
  listAvailableSlots: (branchId: number, serviceId: number, date: string) =>
    get<Slot[]>(`/api/branches/${branchId}/available-slots`, { serviceId, date }),

  /** Khách → ONLINE, lễ tân → COUNTER; khách của lịch là chủ hiện tại của thú (A2). */
  book: (body: BookAppointmentRequest) => post<Appointment>('/api/appointments', body),
  list: (query: AppointmentQuery = {}) => get<PageResponse<Appointment>>('/api/appointments', query),
  get: (appointmentId: number) => get<Appointment>(`/api/appointments/${appointmentId}`),
  /** Cùng chi nhánh, thú, dịch vụ; giữ mã lịch hẹn (BR-LH-06). */
  reschedule: (appointmentId: number, body: RescheduleRequest) =>
    post<Appointment>(`/api/appointments/${appointmentId}/reschedule`, body),
  cancel: (appointmentId: number, body: CancelAppointmentRequest = {}) =>
    post<Appointment>(`/api/appointments/${appointmentId}/cancel`, body),

  myRestriction: () => get<RestrictionStatus>('/api/me/booking-restriction'),
  customerRestriction: (customerId: number) => get<RestrictionStatus>(`/api/customers/${customerId}/booking-restriction`),
  /** 409 khi khách không còn bị hạn chế. */
  liftRestriction: (customerId: number, body: LiftRestrictionRequest) =>
    post<RestrictionStatus>(`/api/customers/${customerId}/booking-restriction/lift`, body),
};
