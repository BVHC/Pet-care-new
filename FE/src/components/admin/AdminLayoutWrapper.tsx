import { Outlet, useLocation } from 'react-router-dom';
import { AdminSidebar, rolesForPath } from './AdminLayout';
import { AdminHeader } from './AdminHeader';
import { useState } from 'react';
import { RequireRole } from '../../shared/components/auth/RequireRole';
import { ROUTES } from '../../shared/constants/routes';

export function AdminLayout() {
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => {
    return localStorage.getItem('sidebar-collapsed') === 'true';
  });

  const toggleSidebar = () => {
    setCollapsed(prev => {
      const next = !prev;
      localStorage.setItem('sidebar-collapsed', String(next));
      return next;
    });
  };

  return (
    <RequireRole roles={rolesForPath(location.pathname)} loginPath={ROUTES.staffLogin}>
      <div className="flex h-screen bg-gray-50 dark:bg-slate-900 overflow-hidden">
        <AdminSidebar collapsed={collapsed} onToggle={toggleSidebar} />
        <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
          <AdminHeader collapsed={collapsed} onToggleSidebar={toggleSidebar} />
          <main className="flex-1 overflow-y-auto p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </RequireRole>
  );
}
