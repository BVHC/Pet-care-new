import { Outlet, Navigate, useLocation } from 'react-router-dom';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { AdminSidebar, routeAllowed } from './AdminLayout';
import { AdminHeader } from './AdminHeader';
import { useState } from 'react';

export function AdminLayout() {
  const { isAuthenticated, user } = useAdminSession();
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

  // Redirect to login if not authenticated
  if (!isAuthenticated || !user) {
    return <Navigate to="/admin/login" state={{ from: location }} replace />;
  }

  // Check role-based route permission
  if (!routeAllowed(location.pathname, user.role)) {
    return <Navigate to="/admin/dashboard" replace />;
  }

  return (
    <div className="flex h-screen bg-gray-50 dark:bg-slate-900 overflow-hidden">
      <AdminSidebar collapsed={collapsed} onToggle={toggleSidebar} />
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <AdminHeader collapsed={collapsed} onToggleSidebar={toggleSidebar} />
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
