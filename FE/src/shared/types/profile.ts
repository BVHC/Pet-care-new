// Hồ sơ cá nhân (UC06) và liên kết hồ sơ (UC07) — theo docs/api/identity-v1.md (Me, Profile link)
// và docs/api/customer-v1.md (My profile) mục Schema.
import type { AccountSummary } from './auth';

export interface StaffProfile {
  accountId: number;
  fullName: string;
  avatarUrl?: string | null;
  phone: string;
  branchId?: number | null;
  /** Chỉ VET (BR-TK-20). */
  specialty?: string | null;
  bio?: string | null;
}

export interface MeResponse {
  account: AccountSummary;
  /** Chỉ nhân viên. */
  staffProfile?: StaffProfile | null;
  /** Chỉ khách. */
  customerId?: number | null;
  linkDecisionPending: boolean;
}

/** null / không gửi = giữ nguyên; chuỗi rỗng = xóa (`avatarUrl`, `specialty`, `bio`). */
export interface UpdateStaffProfileRequest {
  fullName?: string;
  avatarUrl?: string;
  phone?: string;
  specialty?: string;
  bio?: string;
}

export type CreatedChannel = 'ONLINE' | 'COUNTER';

export interface CustomerProfile {
  customerId: number;
  fullName: string;
  phone?: string | null;
  /** Email của tài khoản, chỉ đọc (BR-TK-15). */
  email?: string | null;
  avatarUrl?: string | null;
  /** COUNTER: tài khoản đã gắn vào hồ sơ tại quầy. */
  createdChannel: CreatedChannel;
  hasAccount: boolean;
  linkDecisionPending: boolean;
}

export interface UpdateMyCustomerProfileRequest {
  fullName?: string;
  phone?: string;
  avatarUrl?: string;
}

export interface Address {
  addressId: number;
  receiverName: string;
  receiverPhone: string;
  addressLine: string;
  ward?: string | null;
  province: string;
  isDefault: boolean;
}

export interface AddressRequest {
  receiverName: string;
  receiverPhone: string;
  addressLine: string;
  ward?: string;
  province: string;
  isDefault?: boolean;
}

/** Cờ mặc định chỉ đổi qua set-default. */
export type UpdateAddressRequest = Partial<Omit<AddressRequest, 'isDefault'>>;

/** Hồ sơ tại quầy chưa liên kết, họ tên đã che (BR-TK-19). */
export interface LinkCandidate {
  customerId: number;
  maskedFullName: string;
  /** Không có email thì không liên kết online được, phải ra quầy. */
  hasEmail: boolean;
}

export interface LinkOtpRequest {
  customerId: number;
  /** SĐT đã dùng để tìm ứng viên (khách không có cờ tự liên kết). */
  phone?: string;
}

export interface LinkConfirmRequest {
  customerId: number;
  code: string;
}

export interface LinkResult {
  customerId: number;
}
