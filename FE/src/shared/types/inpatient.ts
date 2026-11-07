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

/** Độ dài tối đa của yaml (`maxLength`). `summary` (tóm tắt điều trị) không giới hạn (TEXT). */
export const INPATIENT_LIMITS = { content: 500, doneNote: 300, reason: 300, observationNote: 500 } as const;

/**
 * Khoảng giá trị chỉ số theo dõi. Hợp đồng chỉ ghi "giá trị ngoài khoảng → 400 BR-LT-16" mà không nêu khoảng: lấy theo kiểu cột của 09 §C
 * (`temperature_c` NUMERIC(4,1), `heart_rate` / `respiratory_rate` SMALLINT, `weight_kg` NUMERIC(6,2)) và phải dương (cân nặng theo BR-KH-04).
 */
export const OBSERVATION_BOUNDS = {
  temperatureC: { min: 0.1, max: 999.9, decimals: 1 },
  heartRate: { min: 1, max: 32767 },
  respiratoryRate: { min: 1, max: 32767 },
  weightKg: { min: 0.01, max: 9999.99, decimals: 2 },
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
