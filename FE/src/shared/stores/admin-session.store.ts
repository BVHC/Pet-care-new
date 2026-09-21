import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { SessionUser, Role } from '../types/admin';

interface AdminSessionState {
  user: SessionUser | null;
  isAuthenticated: boolean;
  login: (user: SessionUser) => void;
  logout: () => void;
}

export const useAdminSession = create<AdminSessionState>()(
  persist(
    (set) => ({
      user: null,
      isAuthenticated: false,
      login: (user) => set({ user, isAuthenticated: true }),
      logout: () => set({ user: null, isAuthenticated: false }),
    }),
    {
      name: 'admin-session',
    }
  )
);
