// Nội trú (UC55, UC56, BR-LT-13…17) theo docs/api/inpatient-v1.md + openapi/inpatient-v1.yaml #1–10. Máy trạng thái 03 #24 (nhập viện), #25 (y lệnh). Trường `?` có thể vắng hoặc null.

export type AdmissionStatus = 'ADMITTED' | 'DISCHARGED';
export type DischargeOutcome = 'HOME' | 'DECEASED';
export type AdmissionOrderType = 'MEDICATION' | 'FEEDING' | 'MONITORING' | 'PROCEDURE' | 'OTHER';
export type AdmissionOrderStatus = 'ORDERED' | 'DONE' | 'CANCELLED';
export type Appetite = 'NORMAL' | 'REDUCED' | 'NONE';

export const ADMISSION_STATUSES: readonly AdmissionStatus[] = ['ADMITTED', 'DISCHARGED'];
export const DISCHARGE_OUTCOMES: readonly DischargeOutcome[] = ['HOME', 'DECEASED'];
export const ADMISSION_ORDER_TYPES: readonly AdmissionOrderType[] = ['MEDICATION', 'FEEDING', 'MONITORING', 'PROCEDURE', 'OTHER'];
export const ADMISSION_ORDER_STATUSES: readonly AdmissionOrderStatus[] = ['ORDERED', 'DONE', 'CANCELLED'];
export const APPETITES: readonly Appetite[] = ['NORMAL', 'REDUCED', 'NONE'];

export const ADMISSION_STATUS_LABELS: Record<AdmissionStatus, string> = { ADMITTED: 'Đang điều trị', DISCHARGED: 'Đã xuất viện' };
export const DISCHARGE_OUTCOME_LABELS: Record<DischargeOutcome, string> = { HOME: 'Về nhà', DECEASED: 'Thú đã mất' };
export const ADMISSION_ORDER_TYPE_LABELS: Record<AdmissionOrderType, string> = {
  MEDICATION: 'Thuốc',
  FEEDING: 'Cho ăn',
  MONITORING: 'Theo dõi',
  PROCEDURE: 'Thủ thuật',
  OTHER: 'Khác',
};
export const ADMISSION_ORDER_STATUS_LABELS: Record<AdmissionOrderStatus, string> = { ORDERED: 'Chờ thực hiện', DONE: 'Đã thực hiện', CANCELLED: 'Đã hủy' };
export const APPETITE_LABELS: Record<Appetite, string> = { NORMAL: 'Bình thường', REDUCED: 'Giảm', NONE: 'Bỏ ăn' };

/** Độ dài tối đa của yaml (`maxLength`); tóm tắt điều trị tối đa 2000 ký tự (BE `@Size`, đo 25/11/2026). */
export const INPATIENT_LIMITS = { content: 500, doneNote: 300, reason: 300, observationNote: 500, summary: 2000 } as const;

/**
 * Giới hạn lưu trữ của chỉ số theo dõi, đúng BE (`AdmissionObservation.invalidReason`, đo 25/11/2026): khoảng lâm sàng chưa chốt (inpatient-v1 A4) nên nhiệt độ 0 và âm không bị chặn;
 * chỉ chặn theo kiểu cột (`temperature_c` NUMERIC(4,1): |t| < 999.95; nhịp SMALLINT 1…32767; `weight_kg` NUMERIC(6,2): 0.005 ≤ w < 9999.995, cân phải dương theo BR-KH-04).
 */
export const OBSERVATION_BOUNDS = {
  temperatureC: { maxAbsExclusive: 999.95, decimals: 1 },
  heartRate: { min: 1, max: 32767 },
  respiratoryRate: { min: 1, max: 32767 },
  weightKg: { min: 0.005, maxExclusive: 9999.995, decimals: 2 },
} as const;

export interface AdmitRequest {
  /** Nhập viện từ lượt khám IN_PROGRESS của mình. Đúng một trong `visitId` / `boardingBookingId`. */
  visitId?: number | null;
  /** Hoặc chuyển từ đặt chỗ lưu trú CHECKED_IN (tạo Visit điều trị). */
  boardingBookingId?: number | null;
  /** Dịch vụ loại INPATIENT (giá theo ngày, chốt lúc nhập viện). */
  serviceId: number;
  /** Chuồng AVAILABLE; vắng = dùng chuồng đang ở (từ lưu trú). */
  kennelId?: number | null;
}

export interface Admission {
  admissionId: number;
  /** `NT-yyMMdd-xxxx` */
  code: string;
  visitId: number;
  petId: number;
  petName: string;
  branchId: number;
  boardingBookingId?: number | null;
  kennelId?: number | null;
  serviceId: number;
  serviceName: string;
  /** Giá theo ngày, chốt lúc nhập viện. */
  unitPrice: number;
  status: AdmissionStatus;
  admittedAt: string;
  /** Số ngày tính tiền = ngày xuất (hoặc hôm nay nếu còn ADMITTED) − ngày nhập + 1, giờ Việt Nam, tính khi đọc (BR-LT-14, ADR-0124). */
  billedDays: number;
  /** `billedDays` × `unitPrice`: tiền tạm tính; dòng Order chỉ có sau xuất viện. */
  estimatedAmount: number;
  dischargeOutcome?: DischargeOutcome | null;
  dischargeSummary?: string | null;
  dischargedAt?: string | null;
  /** Có sau khi xuất viện. */
  orderLineId?: number | null;
}

export interface AdmissionSummary {
  admissionId: number;
  code: string;
  petName: string;
  status: AdmissionStatus;
  admittedAt: string;
  kennelCode?: string | null;
  billedDays: number;
  estimatedAmount: number;
}

export interface DischargeRequest {
  outcome: DischargeOutcome;
  /** Tóm tắt điều trị (BR-LT-14). */
  summary: string;
}

export interface AdmissionOrder {
  admissionOrderId: number;
  admissionId: number;
  orderType: AdmissionOrderType;
  content: string;
  scheduledAt: string;
  status: AdmissionOrderStatus;
  prescriptionItemId?: number | null;
  doneAt?: string | null;
  doneNote?: string | null;
  cancelReason?: string | null;
}

export interface CreateAdmissionOrderRequest {
  orderType: AdmissionOrderType;
  content: string;
  scheduledAt: string;
  /** Bắt buộc với MEDICATION: dòng đơn của Visit (BR-LT-15). */
  prescriptionItemId?: number | null;
}

export interface CompleteAdmissionOrderRequest {
  note: string;
}

export interface ReasonRequest {
  reason: string;
}

export interface Observation {
  observationId: number;
  admissionId: number;
  recordedByName: string;
  temperatureC?: number | null;
  heartRate?: number | null;
  respiratoryRate?: number | null;
  weightKg?: number | null;
  appetite?: Appetite | null;
  note?: string | null;
  abnormal: boolean;
  createdAt: string;
}

export interface CreateObservationRequest {
  temperatureC?: number | null;
  heartRate?: number | null;
  respiratoryRate?: number | null;
  weightKg?: number | null;
  appetite?: Appetite | null;
  note?: string | null;
  /** true báo VET và khách (BR-LT-16). */
  abnormal: boolean;
}

/** `GET /admissions`: `status` vắng = ADMITTED. */
export interface AdmissionQuery {
  status?: AdmissionStatus;
  page?: number;
  size?: number;
}

export interface AdmissionOrderQuery {
  status?: AdmissionOrderStatus;
}

export interface ObservationQuery {
  page?: number;
  size?: number;
}
