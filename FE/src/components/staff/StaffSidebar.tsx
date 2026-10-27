import { useId } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import {
  Bell, ConciergeBell, Keyboard, LayoutDashboard, LogOut, Moon, PanelLeft, PanelLeftClose, Scissors,
  Stethoscope, Sun, UserRound, type LucideIcon,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { ROUTES } from '../../shared/constants/routes';
import { menuFor } from '../../shared/constants/staff-menu';
import {
  WORKSPACE_LABELS, WORKSPACE_PATHS, getAccessibleWorkspaces, homePathFor, type Workspace,
} from '../../shared/constants/workspaces';
import { notificationCenterPath, useUnreadCount } from '../../shared/hooks/useNotifications';
import { usePendingSalesOrders } from '../../shared/hooks/usePendingSalesOrders';
import { useArrivals, useVisitQueue } from '../../shared/hooks/useVisits';
import { useSession } from '../../shared/stores/session.store';
import { ROLE_LABELS, type Role, type SessionUser } from '../../shared/types/auth';
import { visitPermissions } from '../../shared/types/visit';
import { formatToday } from '../../shared/utils/clinic-format';

const ICONS: Record<Workspace, LucideIcon> = {
  reception: ConciergeBell,
  doctor: Stethoscope,
  grooming: Scissors,
  admin: LayoutDashboard,
};

const ICON_BUTTON =
  'inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink)';
const GROUP_LABEL = 'px-2.5 pb-1 pt-4 text-[11px] font-semibold uppercase tracking-wider text-(--ws-ink-3)';

export interface StaffSidebarProps {
  user: SessionUser;
  /** Rail 64px: icons only, every link keeps its name. */
  collapsed: boolean;
  /** Absent in the phone drawer, which has no rail. */
  onToggleCollapsed?: () => void;
  dark: boolean;
  onToggleTheme: () => void;
  onHelp: () => void;
  /** After a link is followed, so the phone drawer can close. */
  onNavigate?: () => void;
}

/** One sidebar for every staff page: the role's desks on top, its back-office pages below. */
export function StaffSidebar({ user, collapsed, onToggleCollapsed, dark, onToggleTheme, onHelp, onNavigate }: StaffSidebarProps) {
  const workspaces = getAccessibleWorkspaces(user.role);
  const sections = menuFor(user.role);
  const hasDesk = workspaces.some((ws) => ws !== 'admin');
  const toggleLabel = collapsed ? 'Mở rộng thanh bên' : 'Thu gọn thanh bên';

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'flex h-10 items-center gap-3 rounded-lg text-sm',
      collapsed ? 'justify-center' : 'px-2.5',
      isActive
        ? 'bg-(--ws-raised) font-semibold text-(--ws-ink) shadow-[inset_0_0_0_1px_var(--ws-line)]'
        : 'font-medium text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink)',
    );

  return (
    <div className="flex h-full flex-col">
      <div className={cn('flex shrink-0 items-center gap-2 pb-2 pt-5', collapsed ? 'flex-col px-2' : 'px-4')}>
        <Link
          to={homePathFor(user.role)}
          onClick={onNavigate}
          aria-label={collapsed ? 'Pet Care' : undefined}
          className="flex min-w-0 flex-1 items-center gap-3"
        >
          <img src="/imgs/CatSticker.svg" alt="" className="h-9 w-9 shrink-0" />
          {!collapsed && (
            <span className="min-w-0">
              <span className="block font-bold leading-tight">Pet Care</span>
              <span className="block truncate text-xs text-(--ws-ink-3)">{formatToday()}</span>
            </span>
          )}
        </Link>
        <StaffNotificationLink role={user.role} onNavigate={onNavigate} />
        {onToggleCollapsed && (
          <button
            type="button"
            onClick={onToggleCollapsed}
            aria-expanded={!collapsed}
            aria-label={toggleLabel}
            title={toggleLabel}
            className={ICON_BUTTON}
          >
            {collapsed ? <PanelLeft className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
          </button>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {workspaces.length > 0 && (
          <nav aria-label="Bàn làm việc">
            {!collapsed && <p className={GROUP_LABEL}>Bàn làm việc</p>}
            <ul className="flex flex-col gap-0.5">
              {workspaces.map((ws, i) => {
                const Icon = ICONS[ws];
                return (
                  <li key={ws}>
                    <NavLink
                      to={WORKSPACE_PATHS[ws]}
                      onClick={onNavigate}
                      aria-label={WORKSPACE_LABELS[ws]}
                      title={collapsed ? `${WORKSPACE_LABELS[ws]} (Alt ${i + 1})` : undefined}
                      className={linkClass}
                    >
                      <span className="ws-swatch" data-zone={ws} aria-hidden>
                        <Icon className="h-4 w-4" />
                      </span>
                      {!collapsed && (
                        <>
                          <span className="flex-1 truncate">{WORKSPACE_LABELS[ws]}</span>
                          <span className="text-[11px] text-(--ws-ink-3)" aria-hidden>
                            Alt {i + 1}
                          </span>
                        </>
                      )}
                    </NavLink>
                  </li>
                );
              })}
            </ul>
          </nav>
        )}

        {sections.length > 0 && (
          <nav aria-label="Quản trị">
            {sections.map((section) => (
              <div key={section.section} role="group" aria-label={section.section}>
                {collapsed ? <hr className="mx-2 my-2 border-(--ws-line)" /> : <p className={GROUP_LABEL}>{section.section}</p>}
                <ul className="flex flex-col gap-0.5">
                  {section.items.map((item) => (
                    <li key={item.path}>
                      <NavLink
                        to={item.path}
                        onClick={onNavigate}
                        aria-label={collapsed ? item.label : undefined}
                        title={collapsed ? item.label : undefined}
                        className={linkClass}
                      >
                        <item.icon className="h-[18px] w-[18px] shrink-0" aria-hidden />
                        {!collapsed && <span className="truncate">{item.label}</span>}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>
        )}
      </div>

      {hasDesk && !collapsed && <ClinicToday role={user.role} />}

      <SidebarFooter
        user={user}
        collapsed={collapsed}
        dark={dark}
        onToggleTheme={onToggleTheme}
        onHelp={onHelp}
        onNavigate={onNavigate}
      />
    </div>
  );
}

/** UC88: chuông kèm số chưa đọc, mở trang Thông báo; ẩn khi role không mở được trang đó (notificationCenterPath). */
export function StaffNotificationLink({ role, onNavigate }: { role: Role; onNavigate?: () => void }) {
  const unread = useUnreadCount().data ?? 0;
  const path = notificationCenterPath(role);
  if (!path) return null;
  const label = unread > 0 ? `Thông báo, ${unread} chưa đọc` : 'Thông báo';
  return (
    <NavLink
      to={path}
      onClick={onNavigate}
      aria-label={label}
      title={label}
      className={({ isActive }) => cn(ICON_BUTTON, 'relative', isActive && 'bg-(--ws-raised) text-(--ws-ink)')}
    >
      <Bell className="h-4 w-4" />
      {unread > 0 && (
        <span
          aria-hidden
          className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-(--ws-urgent-bg) px-1 text-[10px] font-bold leading-none text-(--ws-urgent-ink) ring-2 ring-(--ws-surface)"
        >
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </NavLink>
  );
}

/**
 * Live counts for the branch, for the roles that work a desk. Each role asks only what the BE allows it (VISIT_ROLES): the check-in list is for the front desk and the
 * branch manager, so a vet or groomer sees "–" there instead of a 403.
 */
function ClinicToday({ role }: { role: Role }) {
  const headingId = useId();
  const perms = visitPermissions(role);
  const arrivals = useArrivals(perms.arrivals);
  const visits = useVisitQueue({}, perms.queue);
  const pending = usePendingSalesOrders();

  const count = (status: 'WAITING' | 'IN_PROGRESS') => visits.data?.filter((v) => v.status === status).length;
  const today = [
    { label: 'Chờ tiếp nhận', value: arrivals.data?.length },
    { label: 'Đang chờ', value: count('WAITING') },
    { label: 'Đang làm', value: count('IN_PROGRESS') },
    { label: 'Chờ thu tiền', value: pending.data?.length },
  ];

  return (
    <section aria-labelledby={headingId} className="mx-3 mb-3 shrink-0 rounded-lg border border-(--ws-line) px-3 py-2.5">
      <h2 id={headingId} className="mb-1 text-xs font-semibold text-(--ws-ink-3)">
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
  );
}

function SidebarFooter({
  user,
  collapsed,
  dark,
  onToggleTheme,
  onHelp,
  onNavigate,
}: {
  user: SessionUser;
  collapsed: boolean;
  dark: boolean;
  onToggleTheme: () => void;
  onHelp: () => void;
  onNavigate?: () => void;
}) {
  const navigate = useNavigate();
  const logout = useSession((s) => s.logout);
  const themeLabel = dark ? 'Chế độ sáng' : 'Chế độ tối';

  return (
    <div className="shrink-0 border-t border-(--ws-line) p-3">
      {!collapsed && (
        <>
          <p className="truncate text-sm font-semibold">{user.name}</p>
          <p className="truncate text-xs text-(--ws-ink-3)">{ROLE_LABELS[user.role]}</p>
        </>
      )}
      <div className={cn('flex items-center gap-1', collapsed && 'flex-col', !collapsed && 'mt-2')}>
        <Link to="/admin/profile" onClick={onNavigate} className={ICON_BUTTON} aria-label="Hồ sơ cá nhân" title="Hồ sơ cá nhân">
          <UserRound className="h-4 w-4" />
        </Link>
        <button type="button" className={ICON_BUTTON} onClick={onToggleTheme} aria-label={themeLabel} title={themeLabel}>
          {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        </button>
        <button type="button" className={ICON_BUTTON} onClick={onHelp} aria-label="Phím tắt" title="Phím tắt (?)">
          <Keyboard className="h-4 w-4" />
        </button>
        <button
          type="button"
          className={cn(ICON_BUTTON, !collapsed && 'ml-auto', 'hover:text-(--ws-urgent-ink)')}
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
    </div>
  );
}

/** Phones and tablets: switch desk from the bottom of the screen. */
export function WorkspaceTabBar({ workspaces }: { workspaces: Workspace[] }) {
  return (
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
  );
}
