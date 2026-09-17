import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '../types';
import {
  authApi,
  decodeUser,
  tokenStore,
  type LoginPayload,
  type RegisterPayload,
  type RegisterResult,
} from '../api/auth.api';

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;

  login: (payload: LoginPayload) => Promise<void>;
  register: (payload: RegisterPayload) => Promise<RegisterResult>;
  verifyOtp: (email: string, otpCode: string) => Promise<void>;
  resendOtp: (email: string) => Promise<void>;
  forgotPassword: (email: string) => Promise<void>;
  resetPassword: (email: string, otpCode: string, newPassword: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Doc lai user tu accessToken con trong localStorage (sau khi F5). */
  hydrate: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      isAuthenticated: false,
      isLoading: false,

      login: async (payload) => {
        set({ isLoading: true });
        try {
          const tokens = await authApi.login(payload);
          tokenStore.save(tokens);
          const user = decodeUser(tokens.accessToken);
          set({ user, isAuthenticated: !!user, isLoading: false });
        } catch (error) {
          set({ isLoading: false });
          throw error;
        }
      },

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

      logout: async () => {
        try {
          await authApi.logout(tokenStore.refresh());
        } catch {
          // Token da het han / BE khong voi toi — van dang xuat phia client.
        }
        tokenStore.clear();
        set({ user: null, isAuthenticated: false });
      },

      hydrate: () => {
        const token = tokenStore.access();
        const user = token ? decodeUser(token) : null;
        set({ user, isAuthenticated: !!user });
      },
    }),
    {
      name: 'auth-storage',
      // Token song trong localStorage duoi key rieng (tokenStore) — chi
      // persist user de tranh hai nguon su that ve token.
      partialize: (state) => ({ user: state.user, isAuthenticated: state.isAuthenticated }),
    }
  )
);
