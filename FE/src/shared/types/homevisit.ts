// Khám tại nhà (UC43, UC51, BR-LH-13, 14, BR-KB-07) theo docs/api/homevisit-v1.md + openapi/homevisit-v1.yaml #1–6. Máy trạng thái 03 #22 (bổ sung cho #3 của lịch hẹn).
// Lịch khám tại nhà là một lịch hẹn nhóm Khám có cờ tại nhà; hủy và đổi giờ dùng `POST /appointments/{id}/cancel`, `…/reschedule` của appointment-v1.

export type HomeVisitStatus = 'BOOKED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export const HOME_VISIT_STATUSES: readonly HomeVisitStatus[] = ['BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

export const HOME_VISIT_STATUS_LABELS: Record<HomeVisitStatus, string> = {
  BOOKED: 'Đã đặt',
  CHECKED_IN: 'Đang khám',
  COMPLETED: 'Đã khám',
  CANCELLED: 'Đã hủy',
  NO_SHOW: 'Không gặp được khách',
};

/** Độ dài tối đa và định dạng của yaml (`maxLength`, `pattern`). */
export const HOME_VISIT_LIMITS = { address: 300, note: 300, contactPhone: 15 } as const;
export const HOME_VISIT_PHONE_PATTERN = /^0[0-9]{9,10}$/;

export interface CreateHomeVisitAppointmentRequest {
  petId: number;
  /** Dịch vụ khám (`EXAM`) đang bật ở chi nhánh. */
  serviceId: number;
  /** Chi nhánh đã bật khám tại nhà (BR-SP-09). */
  branchId: number;
  slotDate: string;
  /** Khung 30 phút (BR-LH-02), `HH:mm`. */
  slotStart: string;
  /** Địa chỉ trong sổ của khách (khách phải dùng cái này). Đúng một trong `addressId` / `address`. */
  addressId?: number | null;
  /** Nhập tay, chỉ lễ tân đặt hộ. */
  address?: string | null;
  contactPhone: string;
  note?: string | null;
  /** Chỉ lễ tân đặt hộ; khách tự đặt thì bỏ qua. */
  customerId?: number | null;
}

export interface HomeVisitAppointment {
  appointmentId: number;
  /** `LH-yyMMdd-nnnn` */
  code: string;
  petId: number;
  petName: string;
  customerId: number;
  serviceId: number;
  serviceName: string;
  branchId: number;
  slotDate: string;
  slotStart: string;
  status: HomeVisitStatus;
  /** Snapshot lúc đặt (BR-SP-11). */
  homeAddress: string;
  homeContactPhone: string;
  /** Snapshot lúc đặt (BR-SP-11), đồng. Phí chỉ phát sinh khi thực hiện (BR-LH-14). */
  homeFee: number;
  note?: string | null;
  /** Có sau khi bắt đầu. */
  visitId?: number | null;
  createdAt: string;
}

export interface HomeVisitAppointmentSummary {
  appointmentId: number;
  code: string;
  petName: string;
  serviceName: string;
  slotDate: string;
  slotStart: string;
  status: HomeVisitStatus;
  homeAddress: string;
  homeFee: number;
}

export interface HomeVisitStarted {
  appointmentId: number;
  visitId: number;
  orderId: number;
}

export interface MarkUnreachableRequest {
  /** Bắt buộc (BR-LH-14). */
  note: string;
}

/** `GET /home-visit-appointments`: `date` vắng = hôm nay (giờ Việt Nam). */
export interface HomeVisitQuery {
  date?: string;
  status?: HomeVisitStatus;
  page?: number;
  size?: number;
}

/** `GET /me/home-visit-appointments`. */
export interface MyHomeVisitQuery {
  status?: HomeVisitStatus;
  page?: number;
  size?: number;
}
