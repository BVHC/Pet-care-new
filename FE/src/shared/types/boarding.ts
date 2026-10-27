// Lưu trú (UC54, UC57–UC60) — theo docs/api/boarding-v1.md (mục Schema). Trường `?` có thể vắng hoặc null (BE trả `null`).
import type { PageResponse } from './index';

/** 03 §7 Chuồng. */
export type KennelStatus = 'AVAILABLE' | 'OCCUPIED' | 'MAINTENANCE';
/** 03 §6 Đặt chỗ lưu trú: 6 trạng thái; `CHECKED_OUT`, `CANCELLED`, `NO_SHOW` là cuối. */
export type BookingStatus = 'BOOKED' | 'CHECKED_IN' | 'OVERDUE' | 'CHECKED_OUT' | 'CANCELLED' | 'NO_SHOW';
/** ERD `end_reason` (RETURNED = TRẢ_THÚ, PET_DECEASED = THÚ_MẤT, BR-LT-09 / 12). */
export type BookingEndReason = 'RETURNED' | 'PET_DECEASED';
export type BookingChannel = 'ONLINE' | 'COUNTER';
/** CLINIC = phòng khám hủy (hết chuồng lúc nhận, ngày nghỉ, thú mất, chuyển chủ — Đặt chỗ#5). */
export type BookingCancelSource = 'CUSTOMER' | 'STAFF' | 'CLINIC';

export const KENNEL_STATUSES: readonly KennelStatus[] = ['AVAILABLE', 'OCCUPIED', 'MAINTENANCE'];
export const BOOKING_STATUSES: readonly BookingStatus[] = ['BOOKED', 'CHECKED_IN', 'OVERDUE', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW'];
/** Đặt chỗ còn thú trong chuồng (BR-LT-07: `inStay=true` của GET /boarding-bookings). */
export const IN_STAY_STATUSES: readonly BookingStatus[] = ['CHECKED_IN', 'OVERDUE'];
/** Đặt chỗ chiếm chỗ / chặn đặt trùng ngày của cùng thú (BR-LT-02, 03). */
export const ACTIVE_BOOKING_STATUSES: readonly BookingStatus[] = ['BOOKED', 'CHECKED_IN', 'OVERDUE'];

export const KENNEL_STATUS_LABELS: Record<KennelStatus, string> = {
  AVAILABLE: 'Trống',
  OCCUPIED: 'Đang có thú',
  MAINTENANCE: 'Bảo trì',
};

export const BOOKING_STATUS_LABELS: Record<BookingStatus, string> = {
  BOOKED: 'Đã đặt',
  CHECKED_IN: 'Đang lưu trú',
  OVERDUE: 'Quá hạn đón',
  CHECKED_OUT: 'Đã trả thú',
  CANCELLED: 'Đã hủy',
  NO_SHOW: 'Không đến',
};

export const END_REASON_LABELS: Record<BookingEndReason, string> = { RETURNED: 'Đã trả thú', PET_DECEASED: 'Thú đã mất' };
export const BOOKING_CHANNEL_LABELS: Record<BookingChannel, string> = { ONLINE: 'Đặt online', COUNTER: 'Đặt tại quầy' };
export const BOOKING_CANCEL_SOURCE_LABELS: Record<BookingCancelSource, string> = {
  CUSTOMER: 'Khách hủy',
  STAFF: 'Lễ tân hủy',
  CLINIC: 'Phòng khám hủy',
};

/** Giá trị mặc định của các [CFG] lưu trú (02 BR-LT-02, 04, 06, 11; docs/api/boarding-v1.md). Chỉ để gợi ý giao diện — BE / mock quyết định. */
export const BOARDING_CFG = {
  minNights: 1,
  maxNights: 30,
  /** BR-LT-04: khách đặt trước ngày nhận tối thiểu / xa nhất. */
  minLeadDays: 1,
  maxAdvanceDays: 60,
  /** BR-LT-06: hủy còn dưới số giờ này trước 00:00 ngày nhận là hủy muộn. */
  lateCancelHours: 24,
  /** BR-LT-09: giờ trả quy định (giờ Việt Nam). */
  checkoutTime: '12:00',
  /** BR-LT-11. */
  careLogMaxPhotos: 5,
  careLogEditWindowMinutes: 60,
} as const;

// ───── Chuồng ─────

export interface Kennel {
  kennelId: number;
  branchId: number;
  kennelTypeId: number;
  kennelTypeName: string;
  code: string;
  status: KennelStatus;
  note?: string | null;
  /** Đặt chỗ `CHECKED_IN` / `OVERDUE` đang giữ chuồng. */
  currentBookingId?: number | null;
}

export interface KennelQuery {
  kennelTypeId?: number;
  status?: KennelStatus;
}

export interface CreateKennelRequest {
  kennelTypeId: number;
  code: string;
  note?: string;
}

export interface UpdateKennelRequest {
  code?: string;
  note?: string;
}

export interface AffectedBookingBrief {
  bookingId: number;
  code: string;
  checkInDate: string;
  checkOutDate: string;
  petName: string;
}

/** Chuyển bảo trì chỉ cảnh báo: không tự hủy đặt chỗ nào (BR-LT-01). */
export interface MaintenanceResult {
  kennel: Kennel;
  shortageBookings: AffectedBookingBrief[];
}

// ───── Còn chỗ ─────

export interface NightAvailability {
  date: string;
  capacity: number;
  occupied: number;
  remaining: number;
}

export interface BoardingAvailability {
  kennelTypeId: number;
  nightlyPrice: number;
  nights: NightAvailability[];
  available: boolean;
}

export interface AvailabilityQuery {
  kennelTypeId: number;
  checkInDate: string;
  checkOutDate: string;
}

// ───── Đặt chỗ ─────

export interface VaccineGap {
  vaccineTypeId: number;
  vaccineTypeName: string;
  lastAdministeredOn?: string | null;
  nextDueDate?: string | null;
}

export interface Booking {
  bookingId: number;
  code: string;
  customerId: number;
  customerName: string;
  petId: number;
  petName: string;
  branchId: number;
  kennelTypeId: number;
  kennelTypeName: string;
  kennelId?: number | null;
  kennelCode?: string | null;
  checkInDate: string;
  checkOutDate: string;
  actualCheckInAt?: string | null;
  actualCheckOutAt?: string | null;
  nightlyPrice: number;
  status: BookingStatus;
  endReason?: BookingEndReason | null;
  channel: BookingChannel;
  lateCancel: boolean;
  cancelSource?: BookingCancelSource | null;
  cancelReason?: string | null;
  overdueSince?: string | null;
  /** Order lưu trú chưa kết thúc (PENDING / PAID) của đặt chỗ đang trong chuồng; BE thật luôn null tới T43 (ADR-0065 D10). */
  activeOrderId?: number | null;
  /** BR-LT-11: thú đang lưu trú chưa có nhật ký hôm nay. */
  missingCareLogToday: boolean;
}

export interface BookingResult {
  booking: Booking;
  /** Thiếu mũi bắt buộc dự kiến vào ngày nhận: chỉ cảnh báo, không chặn (BR-LT-05). */
  vaccineWarnings: VaccineGap[];
}

export interface CreateBookingRequest {
  petId: number;
  branchId: number;
  kennelTypeId: number;
  checkInDate: string;
  checkOutDate: string;
}

export interface BookingQuery {
  status?: BookingStatus;
  petId?: number;
  customerId?: number;
  from?: string;
  to?: string;
  inStay?: boolean;
  missingCareLogToday?: boolean;
  page?: number;
  size?: number;
}

export type BookingPage = PageResponse<Booking>;

export interface ExtendBookingRequest {
  checkOutDate: string;
}

export interface CancelBookingRequest {
  reason?: string;
}

// ───── Nhận / trả thú ─────

export interface CheckInReadiness {
  vaccineGaps: VaccineGap[];
  availableKennels: Kennel[];
  withinOpeningHours: boolean;
  canCheckIn: boolean;
}

export interface CheckInRequest {
  kennelId: number;
  weightKg: number;
  healthCondition: string;
  belongings?: string;
  dietInstructions?: string;
  emergencyPhone: string;
}

export interface CheckoutStarted {
  booking: Booking;
  orderId: number;
  nights: number;
  amount: number;
}

export interface DeceasedEndRequest {
  note: string;
}

// ───── Nhật ký chăm sóc ─────

export interface CareLogAddendum {
  addendumId: number;
  content: string;
  createdBy: number;
  createdAt: string;
}

export interface CareLog {
  careLogId: number;
  bookingId: number;
  logDate: string;
  eating?: string | null;
  drinking?: string | null;
  hygiene?: string | null;
  activity?: string | null;
  note?: string | null;
  photoUrls: string[];
  isAbnormal: boolean;
  recordedBy: number;
  recordedByName: string;
  createdAt: string;
  /** Người ghi chỉ sửa được tới mốc này (1 giờ [CFG], BR-LT-11); sau đó chỉ thêm bản bổ sung. */
  editableUntil: string;
  addenda: CareLogAddendum[];
}

export interface CareLogRequest {
  logDate?: string;
  eating?: string;
  drinking?: string;
  hygiene?: string;
  activity?: string;
  note?: string;
  photoUrls: string[];
  isAbnormal?: boolean;
}

export interface UpdateCareLogRequest {
  eating?: string;
  drinking?: string;
  hygiene?: string;
  activity?: string;
  note?: string;
  photoUrls?: string[];
}

export interface AddendumRequest {
  content: string;
}
