// Chi nhánh, giờ mở cửa, ngày nghỉ, dịch vụ tại chi nhánh, quota — theo docs/api/branch-v1.md (mục Schema).
import type { ServiceGroup } from './catalog';

export type BranchStatus = 'DRAFT' | 'ACTIVE';

export const BRANCH_STATUS_LABELS: Record<BranchStatus, string> = { DRAFT: 'Nháp', ACTIVE: 'Đang hoạt động' };

export interface Branch {
  branchId: number;
  name: string;
  address: string;
  phone: string;
  latitude: number;
  longitude: number;
  status: BranchStatus;
  /** BR-CN-05, chỉ SUPER_MANAGER bật / tắt. */
  acceptsAfterHoursEmergency: boolean;
  activatedAt?: string | null;
}

export interface CreateBranchRequest {
  name: string;
  address: string;
  phone: string;
  latitude: number;
  longitude: number;
  acceptsAfterHoursEmergency?: boolean;
}

export type UpdateBranchRequest = Partial<CreateBranchRequest>;

/** Giờ dạng "HH:mm". */
export interface TimeRange {
  open: string;
  close: string;
}

/** `dayOfWeek` 1 = thứ Hai … 7 = Chủ nhật; 0 khoảng = nghỉ cả ngày (BR-CN-02). */
export interface DayHours {
  dayOfWeek: number;
  ranges: TimeRange[];
}

/** Một bản giờ mở cửa, luôn đủ 7 ngày, áp dụng từ `effectiveFrom`. */
export interface OpeningHoursVersion {
  effectiveFrom: string;
  days: DayHours[];
}

export interface SetOpeningHoursRequest extends OpeningHoursVersion {
  /** Hủy hàng loạt lịch bị ảnh hưởng (BR-CN-04, BR-LH-10). */
  cancelAffected?: boolean;
}

export interface AffectedAppointment {
  appointmentId: number;
  code: string;
  slotDate: string;
  slotStart: string;
  petName: string;
  customerName: string;
  customerPhone?: string | null;
}

export interface AffectedBoarding {
  bookingId: number;
  code: string;
  checkInDate: string;
  checkOutDate: string;
  petName: string;
  customerName: string;
  customerPhone?: string | null;
}

/** Lịch hẹn / đặt chỗ bị ảnh hưởng, hiện trước khi quản lý quyết định hủy hàng loạt. */
export interface ScheduleImpact {
  appointments: AffectedAppointment[];
  boardingBookings: AffectedBoarding[];
}

/** Nghỉ cả ngày (BR-CN-03). */
export interface Holiday {
  holidayId: number;
  holidayDate: string;
  reason?: string | null;
}

export interface CreateHolidayRequest {
  holidayDate: string;
  reason?: string;
  cancelAffected?: boolean;
}

export interface BranchServiceItem {
  serviceId: number;
  name: string;
  group: ServiceGroup;
  enabled: boolean;
}

/** Nhóm Lưu trú không có khung giờ nên không có quota (BR-LH-03). */
export type QuotaGroup = 'MEDICAL' | 'GROOMING';

export const QUOTA_GROUP_LABELS: Record<QuotaGroup, string> = { MEDICAL: 'Khám/Tiêm', GROOMING: 'Thẩm mỹ' };

/** `defaultQuota` null = chưa cấu hình, quota hiệu lực là 1. */
export interface QuotaDefault {
  serviceGroup: QuotaGroup;
  defaultQuota?: number | null;
}

/** Quota riêng của một khung; 0 = khóa khung. */
export interface SlotQuota {
  slotQuotaId: number;
  serviceGroup: QuotaGroup;
  slotDate: string;
  slotStart: string;
  quota: number;
}

export type SetSlotQuotaRequest = Omit<SlotQuota, 'slotQuotaId'>;

/** branch-v1 #20, #21: chỉ chi nhánh ACTIVE (BR-CK-01); `weeklyHours` là bản giờ mở cửa đang áp dụng. */
export interface PublicBranch {
  branchId: number;
  name: string;
  address: string;
  phone: string;
  latitude: number;
  longitude: number;
  acceptsAfterHoursEmergency: boolean;
  weeklyHours: DayHours[];
}
