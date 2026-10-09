// ===========================================
// Auth API — tang HTTP duy nhat, dung axios instance chung (./axios).
// Theo docs/api/identity-v1.md. Login, logout, doi mat khau da dung hop dong;
// cac endpoint dang ky / OTP / quen mat khau con lech (sua o T12).
// ===========================================
import { apiClient } from './axios';
import type { AccountStatus, ChangePasswordRequest, LoginRequest, LoginResponse } from '../types/auth';

/** BE RegisterRequest: email bat buoc, phone tuy chon (RULE-01-10). */
export interface RegisterPayload {
  email: string;
  phone?: string;
  password: string;
  name: string;
}

export interface RegisterResult {
  accountId: string;
  status: AccountStatus;
}

/** Lay message loi that tu ApiResponse cua BE, khong nuot thanh "Network Error". */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const res = (error as { response?: { data?: { message?: string } } })?.response;
  return res?.data?.message || fallback;
}

// ─── Endpoints ───

export const authApi = {
  /** Token chi mang sub/sid/jti (ADR-0003): role, mustChangePassword doc tu `account`. */
  login: async (payload: LoginRequest): Promise<LoginResponse> =>
    (await apiClient.post('/api/auth/login', payload)).data.data,

  register: async (payload: RegisterPayload): Promise<RegisterResult> =>
    (await apiClient.post('/api/auth/register', payload)).data.data,

  verifyOtp: async (email: string, otpCode: string): Promise<void> => {
    await apiClient.post('/api/auth/verify-otp', { email, otpCode });
  },

  resendOtp: async (email: string): Promise<void> => {
    await apiClient.post('/api/auth/otp/resend', { email });
  },

  /** Luon 200 ke ca email khong ton tai — BE co tinh khong tiet lo email nao da dang ky. */
  forgotPassword: async (email: string): Promise<void> => {
    await apiClient.post('/api/auth/forgot-password', { email });
  },
  resetPassword: async (email: string, otpCode: string, newPassword: string): Promise<void> => {
    await apiClient.post('/api/auth/reset-password', { email, otpCode, newPassword });
  },
  logout: async (): Promise<void> => {
    await apiClient.post('/api/auth/logout');
  },

  /** 204; BE giu phien hien tai, huy cac phien khac va go mustChangePassword. */
  changePassword: async (payload: ChangePasswordRequest): Promise<void> => {
    await apiClient.post('/api/me/password', payload);
  },
};
