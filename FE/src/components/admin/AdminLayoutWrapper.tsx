import { Outlet, Navigate, useLocation } from 'react-router-dom';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { AdminSidebar, routeAllowed } from './AdminLayout';
import { AdminHeader } from './AdminHeader';

export function AdminLayout() {
  const { isAuthenticated, user } = useAdminSession();
  const location = useLocation();

  // Redirect to login if not authenticated
  if (!isAuthenticated || !user) {
    return <Navigate to="/admin/login" state={{ from: location }} replace />;
  }

  // Check role-based route permission
  if (!routeAllowed(location.pathname, user.role)) {
    return <Navigate to="/admin/dashboard" replace />;
  }

  return (
    <div className="flex h-screen bg-gray-50">
      <AdminSidebar />
      <div className="flex-1 flex flex-col overflow-hidden">
        <AdminHeader />
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
