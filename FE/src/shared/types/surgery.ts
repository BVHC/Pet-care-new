// Phẫu thuật (UC50, BR-KB-08…11) theo docs/api/surgery-v1.md + openapi/surgery-v1.yaml #1–8. Máy trạng thái 03 #23. Trường `?` có thể vắng hoặc null.

export type SurgeryStatus = 'PLANNED' | 'CONSENTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export const SURGERY_STATUSES: readonly SurgeryStatus[] = ['PLANNED', 'CONSENTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
/** Việc cần làm: mặc định của `GET /surgeries` khi vắng `status` (surgery-v1 #3). */
export const OPEN_SURGERY_STATUSES: readonly SurgeryStatus[] = ['PLANNED', 'CONSENTED', 'IN_PROGRESS'];

export const SURGERY_STATUS_LABELS: Record<SurgeryStatus, string> = {
  PLANNED: 'Chờ cam kết',
  CONSENTED: 'Đã có cam kết',
  IN_PROGRESS: 'Đang phẫu thuật',
  COMPLETED: 'Đã hoàn tất',
  CANCELLED: 'Đã hủy',
};

/** Độ dài tối đa của yaml (`maxLength`). `consentText`, `resultNote` không giới hạn (TEXT). */
export const SURGERY_LIMITS = { planNote: 500, signerName: 100, signerRelation: 50, reason: 300 } as const;

export interface PlanSurgeryRequest {
  /** Dịch vụ loại `SURGERY` đang bật ở chi nhánh (BR-KB-08). */
  serviceId: number;
  planNote?: string | null;
}

export interface ConsentSurgeryRequest {
  /** Rủi ro, chi phí dự kiến, phương pháp; bản chụp, không sửa sau khi lập (BR-KB-09). */
  consentText: string;
  signerName: string;
  /** Chủ thú, người đại diện… */
  signerRelation: string;
}

export interface CompleteSurgeryRequest {
  resultNote: string;
}

export interface CancelSurgeryRequest {
  reason: string;
}

export interface SurgerySummary {
  surgeryId: number;
  visitId: number;
  petName: string;
  serviceName: string;
  status: SurgeryStatus;
  createdAt: string;
}

export interface Surgery {
  surgeryId: number;
  visitId: number;
  petId: number;
  petName: string;
  branchId: number;
  serviceId: number;
  serviceName: string;
  status: SurgeryStatus;
  planNote?: string | null;
  consentText?: string | null;
  consentSignerName?: string | null;
  consentSignerRelation?: string | null;
  consentedAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  resultNote?: string | null;
  cancelReason?: string | null;
  /** Có sau khi hoàn tất. */
  orderLineId?: number | null;
  createdAt: string;
}

/** `GET /surgeries`: `status` vắng = PLANNED + CONSENTED + IN_PROGRESS. */
export interface SurgeryQuery {
  status?: SurgeryStatus;
  page?: number;
  size?: number;
}
