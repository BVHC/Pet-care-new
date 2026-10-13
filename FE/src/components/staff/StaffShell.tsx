import { Suspense, useEffect, useLayoutEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Menu } from 'lucide-react';
import './workspace.css';
import { cn } from '../../lib/utils';
import { Sheet, SheetContent, SheetTitle } from '../ui/sheet';
import { pageLabel } from '../../shared/constants/staff-menu';
import { WORKSPACE_PATHS, getAccessibleWorkspaces, workspaceForPath } from '../../shared/constants/workspaces';
import { useHotkeys } from '../../shared/hooks/useHotkeys';
import { useTheme } from '../../shared/hooks/useTheme';
import { useClinicSync, useHandoffNotices } from '../../shared/hooks/useWorkspaceSignals';
import { useSessionUser } from '../../shared/stores/session.store';
import { observeWebVitals, track } from '../../shared/utils/telemetry';
import { LiveRegion } from './LiveRegion';
import { ShortcutHelp } from './ShortcutHelp';
import { StaffSidebar, WorkspaceTabBar } from './StaffSidebar';
import { WorkspaceErrorBoundary } from './WorkspaceErrorBoundary';
import { RowsSkeleton } from './ui';

const COLLAPSED_KEY = 'sidebar-collapsed';

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === 'true';
  } catch {
    return false; // storage blocked: start open
  }
}

/** One frame for every staff page, /staff/* and /admin/*: the sidebar mounts once and stays put. Guarded by RequireRole. */
export function StaffShell() {
  const user = useSessionUser();
  return user ? <Shell user={user} /> : null;
}

function Shell({ user }: { user: NonNullable<ReturnType<typeof useSessionUser>> }) {
  const location = useLocation();
  const navigate = useNavigate();
  const workspaces = getAccessibleWorkspaces(user.role);
  const current = workspaceForPath(location.pathname);
  // Reception, exam room and grooming fill the screen and scroll per column; every other page scrolls as a page.
  const zone = current === 'admin' ? null : current;
  const [helpOpen, setHelpOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const theme = useTheme();

  useClinicSync();
  useHandoffNotices(current, user.id);

  // Before paint: back-office colours follow the workspace palette (workspace.css), each desk gets its zone colour.
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.dataset.staffShell = '';
    if (zone) root.dataset.wsZone = zone;
    return () => {
      delete root.dataset.staffShell;
      delete root.dataset.wsZone;
    };
  }, [zone]);

  useEffect(() => {
    if (current) track('workspace_open', { workspace: current });
  }, [current]);

  useEffect(() => {
    observeWebVitals();
  }, []);

  useHotkeys({
    '?': () => setHelpOpen(true),
    ...Object.fromEntries(workspaces.map((ws, i) => [`alt+${i + 1}`, () => navigate(WORKSPACE_PATHS[ws])])),
  });

  const toggleCollapsed = () => {
    const next = !collapsed;
    setCollapsed(next);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(next));
    } catch {
      // storage blocked: the rail lasts for this page only
    }
  };

  const sidebar = { user, dark: theme.dark, onToggleTheme: theme.toggle, onHelp: () => setHelpOpen(true) };

  return (
    <div className="ws flex h-dvh flex-col overflow-hidden lg:flex-row">
      <a
        href="#ws-main"
        className="sr-only rounded-md bg-(--ws-surface) px-3 py-2 text-sm font-semibold focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50"
      >
        Bỏ qua đến nội dung chính
      </a>

      <aside
        aria-label="Thanh bên"
        className={cn(
          'hidden shrink-0 overflow-hidden border-r border-(--ws-line) bg-(--ws-surface) transition-[width] duration-200 motion-reduce:transition-none lg:block',
          collapsed ? 'w-16' : 'w-60',
        )}
      >
        <StaffSidebar {...sidebar} collapsed={collapsed} onToggleCollapsed={toggleCollapsed} />
      </aside>

      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-(--ws-line) bg-(--ws-surface) px-2 lg:hidden">
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Mở menu"
          className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-(--ws-ink-2) hover:bg-(--ws-raised)"
        >
          <Menu className="h-5 w-5" />
        </button>
        <span className="truncate font-semibold">{pageLabel(location.pathname)}</span>
      </header>

      <main
        id="ws-main"
        tabIndex={-1}
        className={cn(
          'min-h-0 min-w-0 flex-1 overflow-y-auto outline-none',
          workspaces.length > 0 && 'pb-16 lg:pb-0',
          zone && 'lg:overflow-hidden',
        )}
      >
        <WorkspaceErrorBoundary resetKey={location.pathname}>
          <Suspense fallback={<RowsSkeleton rows={6} />}>
            {zone ? (
              <Outlet />
            ) : (
              <div className="p-4 lg:p-6">
                <Outlet />
              </div>
            )}
          </Suspense>
        </WorkspaceErrorBoundary>
      </main>

      {workspaces.length > 0 && <WorkspaceTabBar workspaces={workspaces} />}

      <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
        <SheetContent side="left" className="ws-portal w-[264px] border-(--ws-line) bg-(--ws-surface) p-0 text-(--ws-ink)">
          <SheetTitle className="sr-only">Menu</SheetTitle>
          <StaffSidebar {...sidebar} collapsed={false} onNavigate={() => setMenuOpen(false)} />
        </SheetContent>
      </Sheet>

      <ShortcutHelp open={helpOpen} onOpenChange={setHelpOpen} workspace={current} />
      <LiveRegion />
    </div>
  );
}
