// Tài khoản & phiên đăng nhập — theo docs/api/identity-v1.md (mục Kiểu dữ liệu).

/** Role cố định (04 §1); A05–A08 gắn với một chi nhánh. */
export type Role = 'CUSTOMER' | 'ADMIN' | 'SUPER_MANAGER' | 'BRANCH_MANAGER' | 'RECEPTIONIST' | 'VET' | 'CARETAKER';

export const STAFF_ROLES: readonly Role[] = ['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
export const ALL_ROLES: readonly Role[] = ['CUSTOMER', ...STAFF_ROLES];

export const ROLE_LABELS: Record<Role, string> = {
  CUSTOMER: 'Khách hàng',
  ADMIN: 'Quản trị hệ thống',
  SUPER_MANAGER: 'Quản lý chuỗi',
  BRANCH_MANAGER: 'Quản lý chi nhánh',
  RECEPTIONIST: 'Lễ tân',
  VET: 'Bác sĩ thú y',
  CARETAKER: 'Nhân viên chăm sóc',
};

export const isStaff = (role: Role) => role !== 'CUSTOMER';

/** 03 #1. Khóa là cờ `isLocked` riêng, không phải một trạng thái. */
export type AccountStatus = 'PENDING' | 'ACTIVE' | 'DISABLED';

export interface AccountSummary {
  id: number;
  email: string;
  role: Role;
  status: AccountStatus;
  isLocked: boolean;
  mustChangePassword: boolean;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresAt: string;
  account: AccountSummary;
  linkDecisionPending: boolean;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** BR-TK-02: phải tick cả hai ô. */
export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  phone?: string;
  isAdult: boolean;
  termsAccepted: boolean;
}

export interface RegistrationResponse {
  accountId: number;
  email: string;
  status: 'PENDING';
  otpResendAvailableAt: string;
}

export interface VerifyOtpRequest {
  email: string;
  code: string;
}

export interface VerificationResponse {
  accountId: number;
  status: 'ACTIVE';
  linkDecisionPending: boolean;
}

/** Quên mật khẩu luôn trả `maskedEmail: null` (BR-TK-10). */
export interface OtpSentResponse {
  resendAvailableAt: string;
  maskedEmail?: string | null;
}

export interface ResetPasswordRequest {
  email: string;
  code: string;
  newPassword: string;
}

/** Người đang đăng nhập, dạng UI nhân viên dùng (`id` dạng chuỗi như mã nhân viên của clinic-db). */
export interface SessionUser {
  id: string;
  email: string;
  name: string;
  role: Role;
}
