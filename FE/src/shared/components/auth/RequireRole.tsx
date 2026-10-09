import type { ReactNode } from 'react';
import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { ROUTES } from '../../constants/routes';
import { homePathFor } from '../../constants/workspaces';
import { useAccount, useSession } from '../../stores/session.store';
import { ROLE_LABELS, type AccountSummary, type Role } from '../../types/auth';

/**
 * Chặn route theo thứ tự: chưa đăng nhập → trang đăng nhập; còn mustChangePassword →
 * màn đổi mật khẩu (BR-TK-17); sai role → 403. Hai lần chuyển trang đều giữ `from`.
 */
export function RequireRole({
  roles,
  loginPath = ROUTES.login,
  children,
}: {
  roles: readonly Role[];
  loginPath?: string;
  children?: ReactNode;
}) {
  const account = useAccount();
  const location = useLocation();
  const from = location.pathname + location.search;

  if (!account) return <Navigate to={loginPath} replace state={{ from }} />;
  if (account.mustChangePassword) return <Navigate to={ROUTES.changePassword} replace state={{ from }} />;
  if (!roles.includes(account.role)) return <Forbidden account={account} />;
  return children ? <>{children}</> : <Outlet />;
}

function Forbidden({ account }: { account: AccountSummary }) {
  const logout = useSession((s) => s.logout);
  return (
    <section className="mx-auto flex max-w-md flex-col items-center gap-4 px-6 py-24 text-center">
      <p className="text-5xl font-black text-accent">403</p>
      <h1 className="text-xl font-bold text-(--text-primary)">Bạn không có quyền vào trang này</h1>
      <p className="text-sm text-(--text-secondary)">
        Tài khoản {account.email} ({ROLE_LABELS[account.role]}) không được phép mở trang này.
      </p>
      <div className="flex gap-3">
        <Link
          to={homePathFor(account.role)}
          className="rounded-full bg-accent px-5 py-2.5 text-sm font-bold text-white hover:bg-accent-hover"
        >
          Về trang của tôi
        </Link>
        <button
          type="button"
          onClick={() => void logout()}
          className="rounded-full border border-(--border-color) px-5 py-2.5 text-sm font-bold text-(--text-primary)"
        >
          Đăng xuất
        </button>
      </div>
    </section>
  );
}
