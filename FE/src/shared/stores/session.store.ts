import { useMemo } from 'react';
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { authApi } from '../api/auth.api';
import { bindAuth } from '../api/axios';
import { getMe } from '../api/profile.api';
import type { AccountSummary, LoginRequest, SessionUser } from '../types/auth';

// Phiên đăng nhập duy nhất cho mọi role: một endpoint POST /api/auth/login (identity-v1).

interface SessionData {
  token: string | null;
  /** Hạn tuyệt đối của phiên (ADR-0003, không gia hạn). */
  expiresAt: string | null;
  account: AccountSummary | null;
  linkDecisionPending: boolean;
  /** Họ tên nhân viên từ GET /me; null thì UI hiện email. */
  fullName: string | null;
}

interface SessionState extends SessionData {
  login: (req: LoginRequest) => Promise<AccountSummary>;
  logout: () => Promise<void>;
  /** Bỏ phiên phía client (BE trả 401). */
  clear: () => void;
  setMustChangePassword: (value: boolean) => void;
}

const EMPTY: SessionData = { token: null, expiresAt: null, account: null, linkDecisionPending: false, fullName: null };

export const useSession = create<SessionState>()(
  persist(
    (set, get) => ({
      ...EMPTY,

      login: async (req) => {
        const res = await authApi.login(req);
        set({ ...EMPTY, token: res.accessToken, expiresAt: res.expiresAt, account: res.account, linkDecisionPending: res.linkDecisionPending });
        try {
          set({ fullName: (await getMe()).staffProfile?.fullName ?? null });
        } catch {
          // BE chưa có GET /me (T8): giữ email làm tên hiển thị.
        }
        return res.account;
      },

      logout: async () => {
        try {
          await authApi.logout();
        } catch {
          // Phiên đã chết phía BE: vẫn đăng xuất phía client.
        }
        get().clear();
      },

      clear: () => set(EMPTY),

      setMustChangePassword: (value) =>
        set((s) => (s.account ? { account: { ...s.account, mustChangePassword: value } } : {})),
    }),
    {
      name: 'petcare-session',
      partialize: ({ token, expiresAt, account, linkDecisionPending, fullName }): SessionData => ({
        token,
        expiresAt,
        account,
        linkDecisionPending,
        fullName,
      }),
    },
  ),
);

bindAuth({
  token: () => useSession.getState().token,
  onUnauthorized: () => useSession.getState().clear(),
  onMustChangePassword: () => useSession.getState().setMustChangePassword(true),
});

/** Tài khoản đang đăng nhập; null nếu chưa đăng nhập hoặc đã quá `expiresAt`. */
export function activeAccount(s: Pick<SessionData, 'account' | 'expiresAt'>, now = Date.now()): AccountSummary | null {
  if (!s.account || (s.expiresAt && Date.parse(s.expiresAt) <= now)) return null;
  return s.account;
}

export const useAccount = () => useSession((s) => activeAccount(s));

export function useSessionUser(): SessionUser | null {
  const account = useAccount();
  const fullName = useSession((s) => s.fullName);
  return useMemo(
    () => account && { id: String(account.id), email: account.email, name: fullName ?? account.email, role: account.role },
    [account, fullName],
  );
}
