import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import {
  WORKSPACE_PATHS,
  canAccessWorkspace,
  getDefaultWorkspace,
  type Workspace,
} from '../../shared/constants/workspaces';

/** Route guard: a workspace the user's roles do not cover sends them to their own home. */
export function WorkspaceRoute({ workspace, children }: { workspace: Workspace; children: ReactNode }) {
  const user = useAdminSession((s) => s.user);
  if (!user) return <Navigate to="/admin/login" replace />;
  if (!canAccessWorkspace(user, workspace)) {
    const home = getDefaultWorkspace(user);
    return <Navigate to={home ? WORKSPACE_PATHS[home] : '/admin/login'} replace />;
  }
  return <>{children}</>;
}

/** /staff → the signed-in user's default workspace (primary role). */
export function StaffHome() {
  const user = useAdminSession((s) => s.user);
  const home = user ? getDefaultWorkspace(user) : null;
  return <Navigate to={home ? WORKSPACE_PATHS[home] : '/admin/login'} replace />;
}
