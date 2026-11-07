// Khám tại nhà (UC43, UC51) theo docs/api/homevisit-v1.md #1–6. Khách đặt cho thú của mình (địa chỉ trong sổ địa chỉ), lễ tân đặt hộ ở chi nhánh của mình; VET của chi nhánh bắt đầu khám
// hoặc ghi "không gặp được khách". Hủy và đổi giờ dùng `appointmentApi.cancel` / `reschedule` (appointment-v1, BR-LH-14); phí khám tại nhà xem ở fees-v1.
// Lỗi 400 mang mã rule ở cuối message (BR-LH-13, BR-LH-14, BR-KB-07, BR-TN-01…), 409 INVALID_STATE_TRANSITION / CONCURRENCY_CONFLICT (khung vừa hết quota).
// BE tầng 3 chưa cài (T54 chỉ duyệt đặc tả): đang chạy trên mock `homevisit.mock.ts`.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CreateHomeVisitAppointmentRequest,
  HomeVisitAppointment,
  HomeVisitAppointmentSummary,
  HomeVisitQuery,
  HomeVisitStarted,
  MarkUnreachableRequest,
  MyHomeVisitQuery,
} from '../types/homevisit';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const homevisitApi = {
  /** #1 (khách; lễ tân đặt hộ): 201. 400 BR-LH-13 (chi nhánh chưa bật, dịch vụ không phải khám, địa chỉ sai, thú đã mất), BR-LH-04 / 05 / 09; 409 CONCURRENCY_CONFLICT khung vừa hết quota. */
  create: (body: CreateHomeVisitAppointmentRequest) => post<HomeVisitAppointment>('/api/home-visit-appointments', body),
  /** #2 (khách): mới nhất trước (ngày khám giảm dần). */
  listMine: (query: MyHomeVisitQuery = {}) => get<PageResponse<HomeVisitAppointmentSummary>>('/api/me/home-visit-appointments', query),
  /** #3 (lễ tân, VET của chi nhánh): `date` vắng = hôm nay; sắp theo giờ tăng dần. */
  list: (query: HomeVisitQuery = {}) => get<PageResponse<HomeVisitAppointmentSummary>>('/api/home-visit-appointments', query),
  /** #4 (khách chủ lịch; lễ tân, VET của chi nhánh): lịch của người khác hoặc không phải lịch tại nhà → 404. */
  getAppointment: (appointmentId: number) => get<HomeVisitAppointment>(`/api/home-visit-appointments/${appointmentId}`),
  /** #5 (VET của chi nhánh): BOOKED → CHECKED_IN, tạo Visit IN_PROGRESS gán VET và Order có dòng phí khám tại nhà. 400 BR-KB-07 (ngoài ngày hẹn, không phải lịch tại nhà, thú đã mất), BR-TN-01; 409 trạng thái. */
  start: (appointmentId: number) => post<HomeVisitStarted>(`/api/home-visit-appointments/${appointmentId}/start`, {}),
  /** #6 (VET của chi nhánh): BOOKED → NO_SHOW, tính vào hạn chế đặt online; 400 BR-LH-14 ngoài ngày hẹn / thiếu ghi chú; 409 trạng thái. */
  markUnreachable: (appointmentId: number, body: MarkUnreachableRequest) => post<HomeVisitAppointment>(`/api/home-visit-appointments/${appointmentId}/mark-unreachable`, body),
};
