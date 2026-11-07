// Nhân sự (UC34–38) theo docs/api/workforce-v1.md #1–14 (mục Schema + openapi/workforce-v1.yaml). Trường `?` có thể vắng hoặc null.
// Giờ trong ngày là `HH:mm`, ngày là `YYYY-MM-DD`, cả hai theo giờ Việt Nam (00-method §3.6).

/** 03 #12. `ON_LEAVE`, `ABSENT`, `CANCELLED` là trạng thái cuối. */
export const SHIFT_ASSIGNMENT_STATUSES = ['SCHEDULED', 'ON_LEAVE', 'ABSENT', 'CANCELLED'] as const;
export type ShiftAssignmentStatus = (typeof SHIFT_ASSIGNMENT_STATUSES)[number];

export const SHIFT_REQUEST_TYPES = ['LEAVE', 'SWAP'] as const;
export type ShiftRequestType = (typeof SHIFT_REQUEST_TYPES)[number];

/** 03 #11. */
export const SHIFT_REQUEST_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as const;
export type ShiftRequestStatus = (typeof SHIFT_REQUEST_STATUSES)[number];

/** Độ dài của hợp đồng (maxLength) và khoảng ngày tối đa của lịch (BR-NS-03). */
export const WORKFORCE_LIMITS = { name: 60, reason: 300, rangeDays: 62 } as const;

export interface ShiftDefinition {
  shiftDefinitionId: number;
  branchId: number;
  name: string;
  startTime: string;
  endTime: string;
  active: boolean;
}

export interface CreateShiftDefinitionRequest {
  name: string;
  startTime: string;
  endTime: string;
}

/** Chỉ các trường có trong body được đổi (PATCH). Giờ chỉ sửa được khi ca chưa có phân ca nào (BR-NS-01). */
export interface UpdateShiftDefinitionRequest {
  name?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  /** false = ngừng dùng. */
  active?: boolean | null;
}

export interface ShiftAssignment {
  shiftAssignmentId: number;
  branchId: number;
  staffId: number;
  staffName: string;
  shiftDefinitionId: number;
  shiftName: string;
  startTime: string;
  endTime: string;
  workDate: string;
  status: ShiftAssignmentStatus;
  /** Lý do vắng hoặc hủy. */
  reason?: string | null;
  /** Yêu cầu PENDING của phân ca, nếu có (BR-NS-04). */
  pendingRequestId?: number | null;
}

export interface CreateShiftAssignmentRequest {
  staffId: number;
  shiftDefinitionId: number;
  workDate: string;
}

/** Hủy phân ca và ghi vắng đột xuất. */
export interface ReasonRequest {
  reason: string;
}

export interface ShiftRequest {
  shiftRequestId: number;
  shiftAssignmentId: number;
  requesterId: number;
  requesterName: string;
  workDate: string;
  currentShiftName: string;
  type: ShiftRequestType;
  /** Chỉ có với SWAP. */
  targetShiftDefinitionId?: number | null;
  targetShiftName?: string | null;
  reason: string;
  status: ShiftRequestStatus;
  decidedBy?: number | null;
  decisionNote?: string | null;
  closedAt?: string | null;
  createdAt: string;
}

export interface CreateShiftRequestRequest {
  shiftAssignmentId: number;
  type: ShiftRequestType;
  /** Bắt buộc với SWAP, không được có với LEAVE. */
  targetShiftDefinitionId?: number | null;
  reason: string;
}

export interface RejectShiftRequestRequest {
  decisionNote: string;
}

// ─── Tham số truy vấn ───

/** `activeOnly` vắng = false (cả ca ngừng dùng). */
export interface ShiftDefinitionQuery {
  activeOnly?: boolean;
}

/** `from`, `to` bắt buộc, tối đa 62 ngày (BR-NS-03). */
export interface ShiftAssignmentQuery {
  from: string;
  to: string;
  staffId?: number;
  status?: ShiftAssignmentStatus;
  page?: number;
  size?: number;
}

export interface MyShiftAssignmentQuery {
  from: string;
  to: string;
}

/** Danh sách của chi nhánh: `status` vắng = PENDING. Danh sách của tôi: vắng = mọi trạng thái. */
export interface ShiftRequestQuery {
  status?: ShiftRequestStatus;
  page?: number;
  size?: number;
}
