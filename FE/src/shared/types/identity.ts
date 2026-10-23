// Tài khoản & quản trị — theo docs/api/identity-v1.md (mục Schema + Staff/Accounts/Configuration/Audit).
// Tách khỏi auth.ts để auth.ts chỉ chứa kiểu phiên đăng nhập.

import type { AccountStatus, Role } from './auth';

/** Mirror OtpSentResponse từ auth.ts (dùng lại cho email-change). */
export type OtpSentResponse = { resendAvailableAt: string; maskedEmail?: string | null };

/** Role được phép tạo qua POST /api/staff (BR-QT-01: SUPER_MANAGER tạo được tất cả trừ ADMIN). */
export type CreateableStaffRole = Exclude<Role, 'CUSTOMER' | 'ADMIN'>;

/** Role được phép chuyển sang qua POST /api/staff/{id}/change-role. */
export type ChangeableStaffRole = 'BRANCH_MANAGER' | 'RECEPTIONIST' | 'VET' | 'CARETAKER';

export interface StaffResponse {
  accountId: number;
  email: string;
  phone: string;
  fullName: string;
  role: Role;
  status: AccountStatus;
  isLocked: boolean;
  branchId?: number | null;
  specialty?: string | null;
  bio?: string | null;
  mustChangePassword: boolean;
  online: boolean;
}

export interface CreateStaffRequest {
  email: string;
  phone: string;
  fullName: string;
  role: CreateableStaffRole;
  branchId?: number;
}

export interface ChangeRoleRequest {
  role: ChangeableStaffRole;
}

export interface TransferStaffRequest {
  branchId: number;
}

export interface ReactivateStaffRequest {
  branchId?: number | null;
}

export interface EmailChangeRequest {
  newEmail: string;
}

export interface OtpCodeRequest {
  code: string;
}

/** Lễ tân đã đối chiếu CCCD tại quầy; hệ thống không lưu CCCD, chỉ ghi phương thức (BR-TK-16, A6). */
export type VerificationMethod = 'ID_CARD_IN_PERSON';

/** #25: sửa email tài khoản khách / khôi phục tài khoản khi khách mất email (BR-TK-16). */
export interface CustomerEmailChangeRequest {
  newEmail: string;
  verificationMethod: VerificationMethod;
}

/** #27: lễ tân liên kết hồ sơ tại quầy với tài khoản online, không cần OTP (BR-TK-19). */
export interface CounterLinkRequest {
  accountEmail: string;
  verificationMethod: VerificationMethod;
}

export interface StaffQuery {
  branchId?: number;
  role?: Role;
  status?: AccountStatus;
  q?: string;
  page?: number;
  size?: number;
}

// ─── Accounts (UC09) ───

export interface AccountQuery {
  q?: string;
  role?: Role;
  isLocked?: boolean;
  page?: number;
  size?: number;
}

export interface ReasonRequest {
  reason: string;
}

export type PendingWorkType =
  | 'VISIT_IN_PROGRESS'
  | 'VISIT_WAITING'
  | 'CASHIER_SHIFT_OPEN'
  | 'APPOINTMENT_BOOKED'
  | 'BOARDING_ACTIVE'
  | 'ORDER_UNPAID';

export interface PendingWorkItem {
  type: PendingWorkType;
  entityId: number;
  branchId: number;
  needsReassign: boolean;
}

export interface LockResult {
  account: import('./auth').AccountSummary;
  pendingWork: PendingWorkItem[];
}

// ─── Configuration (UC10) ───

export type SystemConfigValueType = 'INT' | 'DECIMAL' | 'BOOL' | 'TIME';

export interface SystemConfig {
  key: string;
  value: string;
  valueType: SystemConfigValueType;
  minValue?: string | null;
  maxValue?: string | null;
  unit?: string | null;
  description: string;
}

export interface UpdateConfigRequest {
  value: string;
}

// ─── Notification templates (UC10) ───

export type NotificationChannel = 'EMAIL' | 'IN_APP';

export interface NotificationTemplate {
  code: string;
  channel: NotificationChannel;
  subject?: string | null;
  body: string;
  allowedVars: string[];
  requiredVars: string[];
  isDefault: boolean;
}

/** identity-v1 #34: `subject` bắt buộc với mọi kênh (kể cả IN_APP), tối đa 255; thiếu hoặc rỗng → 400 VALIDATION_FAILED. */
export interface UpdateTemplateRequest {
  subject: string;
  body: string;
}

// ─── Audit (UC11) ───

export interface AuditLogEntry {
  id: number;
  actorAccountId?: number | null;
  actorEmail?: string | null;
  action: string;
  entityType?: string | null;
  entityId?: number | null;
  beforeData?: Record<string, unknown> | null;
  afterData?: Record<string, unknown> | null;
  reason?: string | null;
  ipAddress?: string | null;
  createdAt: string;
}

export interface AuditQuery {
  actorAccountId?: number;
  action?: string;
  entityType?: string;
  entityId?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
