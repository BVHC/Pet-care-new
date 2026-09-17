// ===========================================
// Auth API — tang HTTP duy nhat, dung axios instance chung (./axios).
// Map 1-1 voi BE AuthController (@RequestMapping "/api/auth").
// ===========================================
import { apiClient } from './axios';
import type { User, UserRole, AccountStatus } from '../types';

export interface LoginPayload {
  email: string;
  password: string;
}

/** BE RegisterRequest: email bat buoc, phone tuy chon (RULE-01-10). */
export interface RegisterPayload {
  email: string;
  phone?: string;
  password: string;
  name: string;
}

/** BE LoginResponse — khong co field `user`, thong tin nam trong claim cua accessToken. */
export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface RegisterResult {
  accountId: string;
  status: AccountStatus;
}

// ─── Token storage (mot bo key duy nhat, khop voi interceptor trong ./axios) ───

const ACCESS_KEY = 'access_token';
const REFRESH_KEY = 'refresh_token';

export const tokenStore = {
  access: () => localStorage.getItem(ACCESS_KEY),
  refresh: () => localStorage.getItem(REFRESH_KEY),
  save: (t: TokenPair) => {
    localStorage.setItem(ACCESS_KEY, t.accessToken);
    localStorage.setItem(REFRESH_KEY, t.refreshToken);
  },
  clear: () => {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};

// ─── JWT decode ───

/**
 * Doc claim tu accessToken. BE dat: sub=userId, accountId, phone, name, role,
 * scope, accountStatus, organizationId, storeId (JwtTokenProvider).
 * Chi decode, KHONG verify chu ky — chu ky do BE kiem tra o moi request.
 */
export function decodeUser(accessToken: string): User | null {
  try {
    const b64 = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0));
    const c = JSON.parse(new TextDecoder().decode(bytes));
    if (!c.sub) return null;
    return {
      userId: c.sub,
      accountId: c.accountId ?? '',
      name: c.name ?? '',
      phone: c.phone ?? undefined,
      role: (c.role as UserRole) ?? 'CUSTOMER',
      accountStatus: c.accountStatus as AccountStatus | undefined,
      organizationId: c.organizationId ?? undefined,
      storeId: c.storeId ?? undefined,
    };
  } catch {
    return null;
  }
}

/** Lay message loi that tu ApiResponse cua BE, khong nuot thanh "Network Error". */
export function apiErrorMessage(error: unknown, fallback: string): string {
  const res = (error as { response?: { data?: { message?: string } } })?.response;
  return res?.data?.message || fallback;
}

// ─── Endpoints ───

export const authApi = {
  login: async (payload: LoginPayload): Promise<TokenPair> =>
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
  logout: async (refreshToken: string | null): Promise<void> => {
    await apiClient.post('/api/auth/logout', refreshToken ? { refreshToken } : {});
  },
};
