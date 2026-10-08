// ===========================================
// Profile Models - T13: Hồ sơ cá nhân + sổ địa chỉ + liên kết hồ sơ
// Theo identity-v1.md và BR-TK-15, BR-TK-18, BR-TK-19
// ===========================================

import type { UserRole, AccountStatus } from '../types';

// === Me Response ===

/** BE MeResponse: thông tin người đang đăng nhập */
export interface MeResponse {
  account: AccountSummary;
  staffProfile?: StaffProfile;
  customerId?: number;
  linkDecisionPending: boolean;
}

/** BE AccountSummary */
export interface AccountSummary {
  id: number;
  email: string;
  role: Role;
  status: AccountStatus;
  isLocked: boolean;
  mustChangePassword: boolean;
}

/** BE StaffProfile - hồ sơ nhân viên */
export interface StaffProfile {
  accountId: number;
  fullName: string;
  avatarUrl?: string;
  phone: string;
  branchId?: number;
  specialty?: string; // Chuyên môn VET
  bio?: string; // Mô tả ngắn VET (BR-TK-20: tối đa 500 ký tự)
}

/** Role enum - theo docs identity-v1.md */
export type Role =
  | 'CUSTOMER'
  | 'ADMIN'
  | 'SUPER_MANAGER'
  | 'BRANCH_MANAGER'
  | 'RECEPTIONIST'
  | 'VET'
  | 'CARETAKER';

// === Staff Profile Update ===

/** BE UpdateStaffProfileRequest - PATCH /me/staff-profile */
export interface UpdateStaffProfileRequest {
  fullName?: string;
  avatarUrl?: string;
  phone?: string;
  specialty?: string;
  bio?: string;
}

// === Address (Sổ địa chỉ) - BR-TK-18: tối đa 5 địa chỉ ===

/** BE Address - sổ địa chỉ của khách */
export interface Address {
  id: number;
  customerId: number;
  label?: string; // Nhãn: "Nhà", "Cơ quan", "Khác"
  recipientName: string;
  phone: string;
  province: string;
  district: string;
  ward: string;
  street: string; // Số nhà, đường
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAddressRequest {
  label?: string;
  recipientName: string;
  phone: string;
  province: string;
  district: string;
  ward: string;
  street: string;
  isDefault?: boolean;
}

export interface UpdateAddressRequest {
  label?: string;
  recipientName?: string;
  phone?: string;
  province?: string;
  district?: string;
  ward?: string;
  street?: string;
  isDefault?: boolean;
}

// === Profile Linking (Liên kết hồ sơ) - BR-TK-19 ===

/** BE LinkCandidate - hồ sơ tại quầy có thể liên kết */
export interface LinkCandidate {
  customerId: number;
  maskedFullName: string; // Đã che: "Ng*** V** A"
  hasEmail: boolean; // Có email mới liên kết được
}

/** BE LinkOtpRequest - POST /me/link/otp */
export interface LinkOtpRequest {
  customerId: number;
}

/** BE LinkConfirmRequest - POST /me/link/confirm */
export interface LinkConfirmRequest {
  customerId: number;
  code: string; // OTP 6 số
}

/** BE LinkResult - kết quả liên kết */
export interface LinkResult {
  customerId: number;
}

// === OTP ===

/** BE OtpSentResponse */
export interface OtpSentResponse {
  resendAvailableAt: string; // ISO datetime
  maskedEmail?: string;
}

// === Vet Public Profile ===

/** BE PublicVet - hồ sơ công khai của bác sĩ (UC15) */
export interface PublicVet {
  accountId: number;
  fullName: string;
  avatarUrl?: string;
  specialty?: string;
  bio?: string;
  branchId: number;
  branchName: string;
}

// === Helper types ===

/** Display name cho role */
export const ROLE_DISPLAY: Record<Role, string> = {
  CUSTOMER: 'Khách hàng',
  ADMIN: 'Quản trị',
  SUPER_MANAGER: 'Quản lý chuỗi',
  BRANCH_MANAGER: 'Quản lý chi nhánh',
  RECEPTIONIST: 'Lễ tân',
  VET: 'Bác sĩ thú y',
  CARETAKER: 'Nhân viên chăm sóc',
};

/** Check nếu user là nhân viên (có hồ sơ staff) */
export function isStaffUser(role: Role): boolean {
  return ['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'].includes(role);
}

/** Check nếu user là VET (có hồ sơ giới thiệu công khai) */
export function isVetUser(role: Role): boolean {
  return role === 'VET';
}
