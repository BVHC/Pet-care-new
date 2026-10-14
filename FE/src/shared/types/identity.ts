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

export interface UpdateTemplateRequest {
  subject?: string | null;
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
