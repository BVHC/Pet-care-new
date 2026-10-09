import { Suspense, useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  ConciergeBell,
  Keyboard,
  LayoutDashboard,
  LogOut,
  Menu,
  Moon,
  RotateCcw,
  Scissors,
  Stethoscope,
  Sun,
  type LucideIcon,
} from 'lucide-react';
import './workspace.css';
import { cn } from '../../lib/utils';
import { Sheet, SheetContent, SheetTitle } from '../ui/sheet';
import { ROUTES } from '../../shared/constants/routes';
import { useSession, useSessionUser } from '../../shared/stores/session.store';
import { ROLE_LABELS, type SessionUser } from '../../shared/types/auth';
import {
  WORKSPACE_LABELS,
  WORKSPACE_PATHS,
  getAccessibleWorkspaces,
  workspaceForPath,
  type Workspace,
} from '../../shared/constants/workspaces';
import { useArrivals, usePendingOrders, useResetDemo, useTodayVisits } from '../../shared/hooks/useClinic';
import { useHotkeys } from '../../shared/hooks/useHotkeys';
import { useTheme } from '../../shared/hooks/useTheme';
import { useClinicSync, useHandoffNotices } from '../../shared/hooks/useWorkspaceSignals';
import { formatToday } from '../../shared/utils/clinic-format';
import { observeWebVitals, track } from '../../shared/utils/telemetry';
import { LiveRegion } from './LiveRegion';
import { ShortcutHelp } from './ShortcutHelp';
import { WorkspaceErrorBoundary } from './WorkspaceErrorBoundary';
import { RowsSkeleton } from './ui';

const ICONS: Record<Workspace, LucideIcon> = {
  reception: ConciergeBell,
  doctor: Stethoscope,
  grooming: Scissors,
  admin: LayoutDashboard,
};

/** Shell for front-line staff: one workspace at a time, the others one keystroke away. Guarded by RequireRole. */
export function WorkspaceLayout() {
  const user = useSessionUser();
  return user && <WorkspaceShell user={user} />;
}

function WorkspaceShell({ user }: { user: SessionUser }) {
  const location = useLocation();
  const navigate = useNavigate();
  const workspaces = getAccessibleWorkspaces(user.role);
  const current = workspaceForPath(location.pathname);
  const [helpOpen, setHelpOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const theme = useTheme();

  useClinicSync();
  useHandoffNotices(current, user.id);

  useEffect(() => {
    const root = document.documentElement;
    if (current && current !== 'admin') root.dataset.wsZone = current;
    else delete root.dataset.wsZone;
    if (current) track('workspace_open', { workspace: current });
  }, [current]);

  useEffect(() => {
    observeWebVitals();
    return () => {
      delete document.documentElement.dataset.wsZone;
    };
  }, []);

  useHotkeys({
    '?': () => setHelpOpen(true),
    ...Object.fromEntries(workspaces.map((ws, i) => [`alt+${i + 1}`, () => navigate(WORKSPACE_PATHS[ws])])),
  });

  const sidebar = (
    <Sidebar
      user={user}
      workspaces={workspaces}
      dark={theme.dark}
      onToggleTheme={theme.toggle}
      onHelp={() => setHelpOpen(true)}
      onNavigate={() => setMenuOpen(false)}
    />
  );

  return (
    <div className="ws flex h-dvh flex-col overflow-hidden lg:flex-row">
      <a
        href="#ws-main"
        className="sr-only rounded-md bg-(--ws-surface) px-3 py-2 text-sm font-semibold focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50"
      >
        Bỏ qua đến nội dung chính
      </a>

      <aside className="hidden w-[232px] shrink-0 border-r border-(--ws-line) bg-(--ws-surface) lg:block">{sidebar}</aside>

      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-(--ws-line) bg-(--ws-surface) px-2 lg:hidden">
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Mở menu"
          className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-(--ws-ink-2) hover:bg-(--ws-raised)"
        >
          <Menu className="h-5 w-5" />
        </button>
        <span className="font-semibold">{current ? WORKSPACE_LABELS[current] : 'Bàn làm việc'}</span>
      </header>

      <main id="ws-main" tabIndex={-1} className="min-h-0 min-w-0 flex-1 overflow-y-auto pb-16 outline-none lg:overflow-hidden lg:pb-0">
        <WorkspaceErrorBoundary resetKey={location.pathname}>
          <Suspense fallback={<RowsSkeleton rows={6} />}>
            <Outlet />
          </Suspense>
        </WorkspaceErrorBoundary>
      </main>

      <nav
        aria-label="Chuyển bàn làm việc"
        className="fixed inset-x-0 bottom-0 z-30 grid border-t border-(--ws-line) bg-(--ws-surface) pb-[env(safe-area-inset-bottom)] lg:hidden"
        style={{ gridTemplateColumns: `repeat(${workspaces.length}, minmax(0, 1fr))` }}
      >
        {workspaces.map((ws) => {
          const Icon = ICONS[ws];
          return (
            <NavLink
              key={ws}
              to={WORKSPACE_PATHS[ws]}
              className="flex h-14 flex-col items-center justify-center gap-0.5 text-[11px] font-medium text-(--ws-ink-3) aria-[current=page]:text-(--ws-accent-ink)"
            >
              <Icon className="h-5 w-5" aria-hidden />
              {WORKSPACE_LABELS[ws]}
            </NavLink>
          );
        })}
      </nav>

      <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
        <SheetContent side="left" className="ws-portal w-[264px] border-(--ws-line) bg-(--ws-surface) p-0 text-(--ws-ink)">
          <SheetTitle className="sr-only">Menu bàn làm việc</SheetTitle>
          {sidebar}
        </SheetContent>
      </Sheet>

      <ShortcutHelp open={helpOpen} onOpenChange={setHelpOpen} workspace={current} />
      <LiveRegion />
    </div>
  );
}

function Sidebar({
  user,
  workspaces,
  dark,
  onToggleTheme,
  onHelp,
  onNavigate,
}: {
  user: SessionUser;
  workspaces: Workspace[];
  dark: boolean;
  onToggleTheme: () => void;
  onHelp: () => void;
  onNavigate: () => void;
}) {
  const navigate = useNavigate();
  const logout = useSession((s) => s.logout);
  const arrivals = useArrivals();
  const visits = useTodayVisits();
  const pending = usePendingOrders();
  const reset = useResetDemo();
  const [confirmReset, setConfirmReset] = useState(false);

  useEffect(() => {
    if (!confirmReset) return;
    const id = window.setTimeout(() => setConfirmReset(false), 4000);
    return () => window.clearTimeout(id);
  }, [confirmReset]);

  const count = (status: 'WAITING' | 'IN_PROGRESS') => visits.data?.filter((v) => v.status === status).length;
  const today = [
    { label: 'Chờ tiếp nhận', value: arrivals.data?.length },
    { label: 'Đang chờ', value: count('WAITING') },
    { label: 'Đang làm', value: count('IN_PROGRESS') },
    { label: 'Chờ thu tiền', value: pending.data?.length },
  ];

  const iconButton =
    'inline-flex h-9 w-9 items-center justify-center rounded-lg text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink)';

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 px-4 pb-4 pt-5">
        <img src="/imgs/CatSticker.svg" alt="" className="h-9 w-9 shrink-0" />
        <div className="min-w-0">
          <p className="font-bold leading-tight">Pet Care</p>
          <p className="truncate text-xs text-(--ws-ink-3)">{formatToday()}</p>
        </div>
      </div>

      <nav aria-label="Bàn làm việc" className="px-2">
        <ul className="flex flex-col gap-0.5">
          {workspaces.map((ws, i) => {
            const Icon = ICONS[ws];
            return (
              <li key={ws}>
                <NavLink
                  to={WORKSPACE_PATHS[ws]}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    cn(
                      'flex h-11 items-center gap-3 rounded-lg px-2.5 text-sm',
                      isActive
                        ? 'bg-(--ws-raised) font-semibold text-(--ws-ink) shadow-[inset_0_0_0_1px_var(--ws-line)]'
                        : 'font-medium text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink)',
                    )
                  }
                >
                  <span className="ws-swatch" data-zone={ws} aria-hidden>
                    <Icon className="h-4 w-4" />
                  </span>
                  <span className="flex-1">{WORKSPACE_LABELS[ws]}</span>
                  <span className="text-[11px] text-(--ws-ink-3)">Alt {i + 1}</span>
                </NavLink>
              </li>
            );
          })}
        </ul>
      </nav>

      <section aria-labelledby="ws-today" className="mx-3 mt-6 rounded-lg border border-(--ws-line) px-3 py-2.5">
        <h2 id="ws-today" className="mb-1 text-xs font-semibold text-(--ws-ink-3)">
          Cả phòng khám hôm nay
        </h2>
        <dl>
          {today.map((row) => (
            <div key={row.label} className="flex items-baseline justify-between py-1 text-sm">
              <dt className="text-(--ws-ink-2)">{row.label}</dt>
              <dd className="ws-num font-semibold">{row.value ?? '–'}</dd>
            </div>
          ))}
        </dl>
      </section>

      <div className="mt-auto border-t border-(--ws-line) p-3">
        <p className="truncate text-sm font-semibold">{user.name}</p>
        <p className="truncate text-xs text-(--ws-ink-3)">{ROLE_LABELS[user.role]}</p>
        <div className="mt-2 flex items-center gap-1">
          <button type="button" className={iconButton} onClick={onToggleTheme} aria-label={dark ? 'Chế độ sáng' : 'Chế độ tối'} title={dark ? 'Chế độ sáng' : 'Chế độ tối'}>
            {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </button>
          <button type="button" className={iconButton} onClick={onHelp} aria-label="Phím tắt" title="Phím tắt (?)">
            <Keyboard className="h-4 w-4" />
          </button>
          <button
            type="button"
            className={cn(iconButton, confirmReset && 'bg-(--ws-urgent-bg) text-(--ws-urgent-ink)')}
            onClick={() => {
              if (!confirmReset) return setConfirmReset(true);
              setConfirmReset(false);
              reset.mutate();
            }}
            aria-label={confirmReset ? 'Bấm lần nữa để đặt lại dữ liệu demo' : 'Đặt lại dữ liệu demo'}
            title="Đặt lại dữ liệu demo"
          >
            <RotateCcw className="h-4 w-4" />
          </button>
          <button
            type="button"
            className={cn(iconButton, 'ml-auto hover:text-(--ws-urgent-ink)')}
            onClick={async () => {
              await logout();
              navigate(ROUTES.staffLogin);
            }}
            aria-label="Đăng xuất"
            title="Đăng xuất"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
        {confirmReset && (
          <p role="status" className="mt-1 text-xs text-(--ws-urgent-ink)">
            Bấm lần nữa để đặt lại dữ liệu demo hôm nay.
          </p>
        )}
      </div>
    </div>
  );
}
