import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User, AuthTokens } from '../types';
import apiClient from '../api/client';

interface AuthState {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;

  // Actions
  setUser: (user: User | null) => void;
  setTokens: (tokens: AuthTokens) => void;
  login: (phone: string, password: string) => Promise<void>;
  register: (data: { phone: string; email?: string; password: string; name: string }) => Promise<void>;
  logout: () => void;
  checkAuth: () => Promise<void>;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      user: null,
      accessToken: null,
      isAuthenticated: false,
      isLoading: false,

      setUser: (user) => set({ user, isAuthenticated: !!user }),

      setTokens: (tokens) => {
        set({ accessToken: tokens.accessToken });
      },

      login: async (phone, password) => {
        set({ isLoading: true });
        try {
          const response = await apiClient.login({ phone, password });
          if (response.data) {
            const { accessToken, user } = response.data;
            set({
              user,
              accessToken,
              isAuthenticated: true,
              isLoading: false,
            });
          }
        } catch (error) {
          set({ isLoading: false });
          throw error;
        }
      },

      register: async (data) => {
        set({ isLoading: true });
        try {
          await apiClient.register(data);
          set({ isLoading: false });
        } catch (error) {
          set({ isLoading: false });
          throw error;
        }
      },

      logout: () => {
        apiClient.logout();
        set({
          user: null,
          accessToken: null,
          isAuthenticated: false,
        });
      },

      checkAuth: async () => {
        const { user } = get();
        if (!user) return;

        try {
          const response = await apiClient.getCurrentUser();
          if (response.data) {
            set({ user: response.data });
          }
        } catch {
          // Token expired, logout
          get().logout();
        }
      },
    }),
    {
      name: 'auth-storage',
      partialize: (state) => ({
        user: state.user,
        accessToken: state.accessToken,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);
