// Bệnh án, cân nặng khi khám, bản bổ sung, tiêm chủng của lượt (UC48, UC49, UC52, UC53) — docs/api/visit-v1.md #10, #12–15, #18–20 và
// schema `MedicalRecord`, `UpdateMedicalRecordRequest`, `VaccinationOptions`…; kiểm lại bằng BE (docs/adr/0066–0070, docs/testcases/T37-fe.md).
// Kê đơn (#16–17, T46): `prescriptions` luôn có mặt trong `MedicalRecord`; BE trả mảng rỗng tới khi T38 ghi đơn thuốc nên đơn thuốc chạy trên mock theo hợp đồng.
// Yêu cầu kê đơn (`AddPrescriptionRequest`, `ShortageResolution`) nằm ở types/visit.ts.
import type { Addendum, PrescriptionItem, VaccinationRecord } from '../../domains/pets/types';

export type { Addendum, PrescriptionItem, VaccinationRecord };

/** visit-v1 `MedicalRecord`. `locked` = lượt đã COMPLETED (BR-KB-01); lượt Khám/Tiêm chưa ghi bệnh án trả khung rỗng (ADR-0066 §1). */
export interface MedicalRecord {
  visitId: number;
  examination: string | null;
  diagnosis: string | null;
  treatmentPlan: string | null;
  /** Chỉ nhân viên; khách không bao giờ nhận (BR-KB-01, BR-KH-07). */
  internalNote: string | null;
  followUpDate: string | null;
  locked: boolean;
  lastEditedBy: number | null;
  prescriptions: PrescriptionItem[];
  /** BE trả mảng rỗng tới khi T28 xong (mapper `MedicalRecordMapper`). */
  vaccinations: VaccinationRecord[];
  /** Chỉ có khi lượt COMPLETED, theo thứ tự ghi. */
  addenda: Addendum[];
}

/** [CFG] `medical_record.follow_up_max_days` (seed BE V2): ngày tái khám sau ngày khám, xa nhất 180 ngày (BR-KB-06). FE không đọc được [CFG] nên chỉ dùng để giới hạn ô ngày; BE vẫn kiểm. */
export const FOLLOW_UP_MAX_DAYS = 180;

/** PUT thay toàn bộ 5 trường: vắng hoặc `null` là xóa giá trị; BE bỏ khoảng trắng hai đầu và đổi chuỗi rỗng thành `null` (ADR-0066 §4). */
export interface UpdateMedicalRecordRequest {
  examination?: string | null;
  diagnosis?: string | null;
  treatmentPlan?: string | null;
  internalNote?: string | null;
  /** `YYYY-MM-DD`; sau ngày khám, xa nhất 180 ngày [CFG] (BR-KB-06). */
  followUpDate?: string | null;
}

/** BR-KH-04: lớn hơn 0, tối đa 9999.99 kg. */
export interface RecordWeightRequest {
  weightKg: number;
}

export interface WeightRecordRef {
  weightRecordId: number;
  weightKg: number;
  measuredAt: string;
}

export interface AddendumRequest {
  content: string;
  /** Vắng = false (khách xem được). */
  isInternal?: boolean;
}

// ─── Tiêm chủng (UC49, BR-KB-04) ───

export interface ProtocolDoseOption {
  protocolId: number;
  doseNumber: number;
  intervalDays: number;
  minAgeWeeks: number;
  requiredForBoarding: boolean;
}

export interface VaccineProductOption {
  productId: number;
  name: string;
  availableQuantity: number;
}

/** `GET /visits/{id}/vaccination-options`: các mũi của phác đồ đúng loài, mũi gợi ý (chỉ tham khảo) và sản phẩm còn tồn khả dụng tại chi nhánh. */
export interface VaccinationOptions {
  vaccineTypeId: number;
  protocol: ProtocolDoseOption[];
  suggestedProtocolId?: number | null;
  lastDose?: VaccinationRecord | null;
  products: VaccineProductOption[];
  /** Thú chưa đủ tuổi tối thiểu của mũi gợi ý: cảnh báo, không chặn. */
  underMinAge: boolean;
}

export interface RecordVaccinationRequest {
  vaccineTypeId: number;
  /** Mũi VET chọn, có thể khác gợi ý. */
  protocolId: number;
  productId: number;
}

/** `warnings` là mã (vd `UNDER_MIN_AGE`), không phải câu chữ: giao diện tự dịch. */
export interface RecordVaccinationResult {
  vaccination: VaccinationRecord;
  warnings: string[];
}

export const VACCINATION_WARNING_LABELS: Record<string, string> = {
  UNDER_MIN_AGE: 'Thú chưa đủ tuổi tối thiểu của mũi này. Bác sĩ vẫn được quyết định tiêm.',
};
