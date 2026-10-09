import { create } from 'zustand';
import { authApi, type RegisterPayload, type RegisterResult } from '../api/auth.api';

// Luong dang ky / OTP / quen mat khau (T12). Phien dang nhap nam o session.store.
interface AuthState {
  isLoading: boolean;

  register: (payload: RegisterPayload) => Promise<RegisterResult>;
  verifyOtp: (email: string, otpCode: string) => Promise<void>;
  resendOtp: (email: string) => Promise<void>;
  forgotPassword: (email: string) => Promise<void>;
  resetPassword: (email: string, otpCode: string, newPassword: string) => Promise<void>;
}

export const useAuthStore = create<AuthState>()((set) => ({
  isLoading: false,

  register: async (payload) => {
    set({ isLoading: true });
    try {
      const result = await authApi.register(payload);
      set({ isLoading: false });
      return result;
    } catch (error) {
      set({ isLoading: false });
      throw error;
    }
  },

  verifyOtp: async (email, otpCode) => {
    set({ isLoading: true });
    try {
      await authApi.verifyOtp(email, otpCode);
      set({ isLoading: false });
    } catch (error) {
      set({ isLoading: false });
      throw error;
    }
  },

  resendOtp: async (email) => {
    await authApi.resendOtp(email);
  },

  forgotPassword: async (email) => {
    await authApi.forgotPassword(email);
  },

  resetPassword: async (email, otpCode, newPassword) => {
    await authApi.resetPassword(email, otpCode, newPassword);
  },
}));
