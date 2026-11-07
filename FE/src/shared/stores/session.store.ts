import { useMemo } from 'react';
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { authApi } from '../api/auth.api';
import { bindAuth } from '../api/axios';
import { profileApi } from '../api/profile.api';
import type { AccountSummary, LoginRequest, SessionUser } from '../types/auth';

// Phiên đăng nhập duy nhất cho mọi role: một endpoint POST /api/auth/login (identity-v1).

interface SessionData {
  token: string | null;
  /**
   * Dùng một lần, đổi sau mỗi lần làm mới (ADR-0091). Lưu cùng access token trong localStorage, nên cùng rủi ro XSS nhưng sống tới hết phiên (12 giờ)
   * thay vì 10 phút; dùng lại token đã dùng hủy cả phiên. Null ở phiên mở trước V27: không làm mới được.
   */
  refreshToken: string | null;
  /** Hạn access token; null ở phiên mở trước V27 (không biết hạn thì không làm mới, không tạm dừng poll). */
  accessTokenExpiresAt: string | null;
  /** Hạn tuyệt đối của phiên (ADR-0003, không gia hạn). */
  expiresAt: string | null;
  account: AccountSummary | null;
  linkDecisionPending: boolean;
  /** Họ tên từ hồ sơ nhân viên / hồ sơ khách; null thì UI hiện email. */
  fullName: string | null;
}

interface SessionState extends SessionData {
  login: (req: LoginRequest) => Promise<AccountSummary>;
  logout: () => Promise<void>;
  /** Bỏ phiên phía client (BE trả 401). */
  clear: () => void;
  setMustChangePassword: (value: boolean) => void;
  setLinkDecisionPending: (value: boolean) => void;
  setFullName: (value: string) => void;
}

const EMPTY: SessionData = {
  token: null,
  refreshToken: null,
  accessTokenExpiresAt: null,
  expiresAt: null,
  account: null,
  linkDecisionPending: false,
  fullName: null,
};

export const useSession = create<SessionState>()(
  persist(
    (set, get) => ({
      ...EMPTY,

      login: async (req) => {
        const res = await authApi.login(req);
        set({
          ...EMPTY,
          token: res.accessToken,
          refreshToken: res.refreshToken,
          accessTokenExpiresAt: res.accessTokenExpiresAt,
          expiresAt: res.expiresAt,
          account: res.account,
          linkDecisionPending: res.linkDecisionPending,
        });
        try {
          const me = await profileApi.me();
          const fullName = me.staffProfile ? me.staffProfile.fullName : (await profileApi.customerProfile()).fullName;
          // BE không trả `branchId` ở login (chỉ `GET /me` → staffProfile): gộp vào phiên để mọi trang nhân viên có chi nhánh ngay.
          const branchId = me.staffProfile?.branchId;
          set({ fullName, linkDecisionPending: me.linkDecisionPending, ...(branchId != null ? { account: { ...res.account, branchId } } : {}) });
        } catch {
          // Chưa đọc được hồ sơ: giữ email làm tên hiển thị.
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

      setLinkDecisionPending: (value) => set({ linkDecisionPending: value }),

      setFullName: (value) => set({ fullName: value }),
    }),
    {
      name: 'petcare-session',
      partialize: ({ token, refreshToken, accessTokenExpiresAt, expiresAt, account, linkDecisionPending, fullName }): SessionData => ({
        token,
        refreshToken,
        accessTokenExpiresAt,
        expiresAt,
        account,
        linkDecisionPending,
        fullName,
      }),
    },
  ),
);

// ─── Làm mới access token (D055, ADR-0091) ───

/** Làm mới khi access token còn dưới ngần này. */
const REFRESH_WINDOW_MS = 5 * 60_000;

/** Có refresh token và access token còn dưới 5 phút (hoặc đã hết). Phiên cũ không có hạn access hoặc refresh token thì không bao giờ cần. */
export const needsRefresh = (s: Pick<SessionData, 'refreshToken' | 'accessTokenExpiresAt'>, now = Date.now()): boolean =>
  !!s.refreshToken && !!s.accessTokenExpiresAt && Date.parse(s.accessTokenExpiresAt) - now < REFRESH_WINDOW_MS;

let inflight: Promise<void> | null = null;

// shortcut: không có navigator.locks (trình duyệt cũ, jsdom) thì chỉ còn single-flight trong tab; hai tab đụng nhau đúng lúc làm mất phiên (đăng nhập lại), nâng cấp nếu cần hỗ trợ.
const underLock = async <T>(task: () => Promise<T>): Promise<T> =>
  typeof navigator !== 'undefined' && navigator.locks ? await navigator.locks.request('petcare-refresh', task) : task();

/**
 * Làm mới access token. Một lượt tại một thời điểm: trong tab (`inflight`) và giữa các tab (Web Locks). Trong khóa đọc lại storage trước, vì tab khác có thể đã
 * xoay token và refresh token cũ dùng lại sẽ hủy cả phiên. `stale` = token vừa nhận 401: chỉ phải làm mới khi storage vẫn giữ đúng token đó; bỏ trống thì
 * làm mới khi `needsRefresh`. Mọi lỗi (401, mất phản hồi) kết thúc phiên rồi ném lại.
 */
export function refreshSession(stale?: string | null): Promise<void> {
  inflight ??= underLock(async () => {
    await useSession.persist.rehydrate();
    const s = useSession.getState();
    if (!s.refreshToken || (stale !== undefined ? s.token !== stale : !needsRefresh(s))) return;
    try {
      const res = await authApi.refresh(s.refreshToken);
      useSession.setState({
        token: res.accessToken,
        refreshToken: res.refreshToken,
        accessTokenExpiresAt: res.accessTokenExpiresAt,
        expiresAt: res.expiresAt,
      });
    } catch (error) {
      useSession.getState().clear();
      throw error;
    }
  }).finally(() => {
    inflight = null;
  });
  return inflight;
}

bindAuth({
  token: () => useSession.getState().token,
  onUnauthorized: () => useSession.getState().clear(),
  onMustChangePassword: () => useSession.getState().setMustChangePassword(true),
  account: () => activeAccount(useSession.getState()),
  // Mọi request đợi lượt làm mới đang chạy; chỉ request của người dùng mới được bắt đầu một lượt (poll thì không bao giờ: tái tạo D024).
  prepare: (poll) => inflight ?? (!poll && needsRefresh(useSession.getState()) ? refreshSession() : null),
  recover: async (sent, poll) => {
    if (sent === null) return false;
    if (poll) {
      await inflight?.catch(() => undefined);
      await useSession.persist.rehydrate();
    } else {
      await refreshSession(sent);
    }
    const { token } = useSession.getState();
    return token !== null && token !== sent;
  },
});

// Thao tác thật của người dùng (chuột, phím) cũng bắt đầu lượt làm mới: trang chỉ toàn poll (hàng đợi lễ tân) không phát request nào của người dùng,
// nên sau khi để rảnh quá hạn, bấm vào đó sẽ chỉ gặp 401. Người rảnh không phát sự kiện nào; không timer, không poll.
if (typeof window !== 'undefined') {
  const onInput = () => {
    if (!inflight && needsRefresh(useSession.getState())) refreshSession().catch(() => undefined);
  };
  window.addEventListener('pointerdown', onInput, { capture: true, passive: true });
  window.addEventListener('keydown', onInput, { capture: true, passive: true });
}

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
