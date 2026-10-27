// Lưu trú (UC54, UC57–UC60) theo docs/api/boarding-v1.md #1–22.
// Chuồng: BRANCH_MANAGER ghi, GET thêm RECEPTIONIST / CARETAKER · đặt chỗ: CUSTOMER, RECEPTIONIST · nhận thú: lễ tân, CARETAKER ·
// trả thú, hủy vì hết chuồng, thú mất: chỉ lễ tân · nhật ký: VET, CARETAKER ghi; khách xem của thú mình.
// Hủy phiên trả thú là thao tác trên Order: salesApi.abortCheckout (sales-v1 #9).
import { apiClient } from './axios';
import type {
  AddendumRequest,
  AvailabilityQuery,
  Booking,
  BoardingAvailability,
  BookingQuery,
  BookingResult,
  CancelBookingRequest,
  CareLog,
  CareLogAddendum,
  CareLogRequest,
  CheckInReadiness,
  CheckInRequest,
  CheckoutStarted,
  CreateBookingRequest,
  CreateKennelRequest,
  DeceasedEndRequest,
  ExtendBookingRequest,
  Kennel,
  KennelQuery,
  MaintenanceResult,
  UpdateCareLogRequest,
  UpdateKennelRequest,
} from '../types/boarding';
import type { PageResponse } from '../types';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const boardingApi = {
  // ───── Chuồng (UC54) ─────
  /** Phạm vi = chi nhánh người gọi; sắp theo mã tăng dần. VET bị 403. */
  listKennels: (query: KennelQuery = {}) => get<Kennel[]>('/api/kennels', query),
  createKennel: (body: CreateKennelRequest) => post<Kennel>('/api/kennels', body),
  updateKennel: (kennelId: number, body: UpdateKennelRequest) => patch<Kennel>(`/api/kennels/${kennelId}`, body),
  /** 204; 409 khi đang có thú hoặc đã có lịch sử lưu trú (chuyển bảo trì thay vì xóa). */
  deleteKennel: async (kennelId: number): Promise<void> => {
    await apiClient.delete(`/api/kennels/${kennelId}`);
  },
  /** Chỉ cảnh báo `shortageBookings`, không tự hủy đặt chỗ nào (BR-LT-01). */
  startMaintenance: (kennelId: number) => post<MaintenanceResult>(`/api/kennels/${kennelId}/start-maintenance`),
  endMaintenance: (kennelId: number) => post<Kennel>(`/api/kennels/${kennelId}/end-maintenance`),

  // ───── Còn chỗ + đặt chỗ (UC58) ─────
  /** Đêm = ngày nhận … ngày trả − 1 (A1). */
  availability: (branchId: number, query: AvailabilityQuery) =>
    get<BoardingAvailability>(`/api/branches/${branchId}/boarding-availability`, query),
  /** Khách → ONLINE, lễ tân → COUNTER; trả kèm cảnh báo thiếu mũi (không chặn). */
  book: (body: CreateBookingRequest) => post<BookingResult>('/api/boarding-bookings', body),
  list: (query: BookingQuery = {}) => get<PageResponse<Booking>>('/api/boarding-bookings', query),
  get: (bookingId: number) => get<Booking>(`/api/boarding-bookings/${bookingId}`),
  extend: (bookingId: number, body: ExtendBookingRequest) => post<Booking>(`/api/boarding-bookings/${bookingId}/extend`, body),
  cancel: (bookingId: number, body: CancelBookingRequest = {}) => post<Booking>(`/api/boarding-bookings/${bookingId}/cancel`, body),

  // ───── Nhận / trả thú (UC59) ─────
  checkInReadiness: (bookingId: number) => get<CheckInReadiness>(`/api/boarding-bookings/${bookingId}/check-in-readiness`),
  checkIn: (bookingId: number, body: CheckInRequest) => post<Booking>(`/api/boarding-bookings/${bookingId}/check-in`, body),
  /** Hết chuồng đúng loại lúc nhận; Đặt chỗ#5, không tính hủy muộn. */
  cancelNoKennel: (bookingId: number) => post<Booking>(`/api/boarding-bookings/${bookingId}/cancel-no-kennel`),
  /** Sinh (hoặc dùng lại) Order BOARDING PENDING; thu tiền bằng POST /payments. */
  startCheckout: (bookingId: number) => post<CheckoutStarted>(`/api/boarding-bookings/${bookingId}/start-checkout`),
  /** Chỉ khi Order lưu trú đã PAID — không có ngoại lệ (BR-LT-09). */
  handOver: (bookingId: number) => post<Booking>(`/api/boarding-bookings/${bookingId}/hand-over`),
  endDeceased: (bookingId: number, body: DeceasedEndRequest) => post<CheckoutStarted>(`/api/boarding-bookings/${bookingId}/end-deceased`, body),

  // ───── Nhật ký chăm sóc (UC57, UC60) ─────
  listCareLogs: (bookingId: number) => get<CareLog[]>(`/api/boarding-bookings/${bookingId}/care-logs`),
  createCareLog: (bookingId: number, body: CareLogRequest) => post<CareLog>(`/api/boarding-bookings/${bookingId}/care-logs`, body),
  /** Người ghi, trong `editableUntil`; sau đó 409 (BR-LT-11). */
  updateCareLog: (careLogId: number, body: UpdateCareLogRequest) => patch<CareLog>(`/api/care-logs/${careLogId}`, body),
  addAddendum: (careLogId: number, body: AddendumRequest) => post<CareLogAddendum>(`/api/care-logs/${careLogId}/addenda`, body),
};
