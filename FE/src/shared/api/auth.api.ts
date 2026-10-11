// ===========================================
// Auth API — tang HTTP duy nhat, dung axios instance chung (./axios).
// Theo docs/api/identity-v1.md (UC01–UC05).
// ===========================================
import { apiClient } from './axios';
import type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  OtpSentResponse,
  RegisterRequest,
  RegistrationResponse,
  ResetPasswordRequest,
  VerificationResponse,
} from '../types/auth';

export const authApi = {
  /** 201, tai khoan PENDING; ma OTP gui qua email (BR-TK-04). */
  register: async (payload: RegisterRequest): Promise<RegistrationResponse> =>
    (await apiClient.post('/api/auth/register', payload)).data.data,

  verifyRegistration: async (email: string, code: string): Promise<VerificationResponse> =>
    (await apiClient.post('/api/auth/register/verify', { email, code })).data.data,

  resendRegistrationOtp: async (email: string): Promise<OtpSentResponse> =>
    (await apiClient.post('/api/auth/register/resend-otp', { email })).data.data,

  /** Token chi mang sub/sid/jti (ADR-0003): role, mustChangePassword doc tu `account`. */
  login: async (payload: LoginRequest): Promise<LoginResponse> =>
    (await apiClient.post('/api/auth/login', payload)).data.data,

  logout: async (): Promise<void> => {
    await apiClient.post('/api/auth/logout');
  },

  /** Luon 202 cung mot noi dung, du email co ton tai hay khong (BR-TK-10). */
  forgotPassword: async (email: string): Promise<OtpSentResponse> =>
    (await apiClient.post('/api/auth/password/forgot', { email })).data.data,

  /** 204; BE huy moi phien cua tai khoan (BR-TK-13). */
  resetPassword: async (payload: ResetPasswordRequest): Promise<void> => {
    await apiClient.post('/api/auth/password/reset', payload);
  },

  /** 204; BE giu phien hien tai, huy cac phien khac va go mustChangePassword. */
  changePassword: async (payload: ChangePasswordRequest): Promise<void> => {
    await apiClient.post('/api/me/password', payload);
  },
};
