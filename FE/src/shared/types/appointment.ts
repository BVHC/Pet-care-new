// Lịch hẹn (UC39, UC40) — theo docs/api/appointment-v1.md (mục Schema). Trường `?` có thể vắng hoặc null.
import type { QuotaGroup } from './branch';

/** 03 §3 Lịch hẹn: 5 trạng thái, không có trạng thái "chờ xác nhận". */
export type AppointmentStatus = 'BOOKED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
/** ONLINE = khách tự đặt, COUNTER = lễ tân đặt hộ (appointment-v1 A2). */
export type AppointmentChannel = 'ONLINE' | 'COUNTER';
/** CLINIC = phòng khám hủy (ngày nghỉ / thu hẹp giờ, thú mất, chuyển chủ — 03 §3 #5). */
export type CancelSource = 'CUSTOMER' | 'STAFF' | 'CLINIC';
export type SlotStatus = 'AVAILABLE' | 'FULL';

export const APPOINTMENT_STATUSES: readonly AppointmentStatus[] = ['BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

export const APPOINTMENT_STATUS_LABELS: Record<AppointmentStatus, string> = {
  BOOKED: 'Đã đặt',
  CHECKED_IN: 'Đã tiếp nhận',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
  NO_SHOW: 'Không đến',
};

export const CHANNEL_LABELS: Record<AppointmentChannel, string> = { ONLINE: 'Đặt online', COUNTER: 'Đặt tại quầy' };

export const CANCEL_SOURCE_LABELS: Record<CancelSource, string> = {
  CUSTOMER: 'Khách hủy',
  STAFF: 'Lễ tân hủy',
  CLINIC: 'Phòng khám hủy',
};

/**
 * Giá trị mặc định của các [CFG] lịch hẹn (02 BR-LH-04, 06, 07, 09). FE không đọc được cấu hình hệ thống
 * (UC10 chỉ ADMIN) nên chỉ dùng để gợi ý trên giao diện; BE / mock vẫn là nơi quyết định.
 */
export const APPOINTMENT_CFG = {
  /** BR-LH-04: khách đặt trước giờ hẹn tối thiểu. */
  minLeadHours: 24,
  /** BR-LH-04: khách đặt xa nhất (tính theo ngày, ADR-0046). */
  maxAdvanceDays: 30,
  /** BR-LH-06: khách đổi trước giờ hẹn tối thiểu. */
  rescheduleMinLeadHours: 12,
  /** BR-LH-06: số lần đổi tối đa, tính chung khách và lễ tân. */
  maxReschedules: 3,
  /** BR-LH-07: hủy khi còn dưới mốc này là hủy muộn. */
  lateCancelHours: 12,
  /** BR-LH-09: số lần không đến / hủy muộn trong cửa sổ thì bị hạn chế. */
  violationThreshold: 3,
  violationWindowDays: 90,
  restrictionDays: 30,
} as const;

export interface Slot {
  /** "HH:mm" */
  slotStart: string;
  /** slotStart + 30 phút */
  slotEnd: string;
  quota: number;
  /** Lịch BOOKED, CHECKED_IN, COMPLETED của khung (BR-LH-03). */
  booked: number;
  /** FULL hiển thị "Hết chỗ"; quota 0 cũng là FULL. */
  status: SlotStatus;
}

export interface BookAppointmentRequest {
  petId: number;
  branchId: number;
  serviceId: number;
  slotDate: string;
  slotStart: string;
  /** ≤ 500 ký tự */
  note?: string | null;
}

export interface Appointment {
  appointmentId: number;
  /** Giữ nguyên khi đổi lịch (BR-LH-06). */
  code: string;
  customerId: number;
  customerName: string;
  petId: number;
  petName: string;
  branchId: number;
  branchName: string;
  serviceId: number;
  serviceName: string;
  serviceGroup: QuotaGroup;
  slotDate: string;
  slotStart: string;
  status: AppointmentStatus;
  channel: AppointmentChannel;
  rescheduleCount: number;
  lateCancel: boolean;
  cancelSource?: CancelSource | null;
  cancelReason?: string | null;
  cancelledAt?: string | null;
  note?: string | null;
  visitId?: number | null;
  /** Theo người xem (ADR-0048): BRANCH_MANAGER, VET, CARETAKER luôn false. */
  canReschedule: boolean;
  canCancel: boolean;
}

export interface AppointmentQuery {
  branchId?: number;
  date?: string;
  from?: string;
  to?: string;
  status?: AppointmentStatus;
  petId?: number;
  customerId?: number;
  /** Chỉ lịch BOOKED hôm nay trong cửa sổ check-in (A3). */
  checkInReady?: boolean;
  page?: number;
  size?: number;
}

export interface RescheduleRequest {
  slotDate: string;
  slotStart: string;
}

export interface CancelAppointmentRequest {
  /** ≤ 300 ký tự, không bắt buộc (A4). */
  reason?: string | null;
}

export interface BookingRestriction {
  restrictionId: number;
  customerId: number;
  startsAt: string;
  endsAt: string;
  /** Số không đến + hủy muộn đã đếm khi tạo hạn chế (ADR-0049). */
  violationCount: number;
}

export interface RestrictionStatus {
  restricted: boolean;
  restriction?: BookingRestriction | null;
}

export interface LiftRestrictionRequest {
  /** Bắt buộc, ≤ 300 ký tự. */
  reason: string;
}
