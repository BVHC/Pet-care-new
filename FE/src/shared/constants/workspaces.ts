import { ALL_STAFF_ROLES, type Role, type SessionUser } from '../types/admin';

export type Workspace = 'reception' | 'doctor' | 'grooming' | 'admin';

/** Display + Alt+1..4 order. */
export const WORKSPACE_ORDER: readonly Workspace[] = ['reception', 'doctor', 'grooming', 'admin'];

export const WORKSPACE_PATHS: Record<Workspace, string> = {
  reception: '/staff/reception',
  doctor: '/staff/doctor',
  grooming: '/staff/grooming',
  admin: '/admin/dashboard',
};

export const WORKSPACE_LABELS: Record<Workspace, string> = {
  reception: 'Lễ tân',
  doctor: 'Phòng khám',
  grooming: 'Grooming',
  admin: 'Quản trị',
};

export const WORKSPACE_ROLES: Record<Workspace, readonly Role[]> = {
  reception: ['RECEPTIONIST', 'STORE_MANAGER', 'SUPER_ADMIN'],
  doctor: ['VETERINARIAN', 'STORE_MANAGER', 'SUPER_ADMIN'],
  grooming: ['GROOMER', 'STORE_MANAGER', 'SUPER_ADMIN'],
  admin: ALL_STAFF_ROLES,
};

const HOME_BY_ROLE: Partial<Record<Role, Workspace>> = {
  RECEPTIONIST: 'reception',
  VETERINARIAN: 'doctor',
  GROOMER: 'grooming',
};

/** Primary role first; sessions persisted before multi-role only carry `role`. */
export function getUserRoles(user: SessionUser): Role[] {
  const others = (user.roles ?? []).filter((r) => r !== user.role);
  return [user.role, ...new Set(others)];
}

export function canAccessWorkspace(user: SessionUser, workspace: Workspace): boolean {
  return getUserRoles(user).some((r) => WORKSPACE_ROLES[workspace].includes(r));
}

export function getAccessibleWorkspaces(user: SessionUser): Workspace[] {
  return WORKSPACE_ORDER.filter((ws) => canAccessWorkspace(user, ws));
}

export function getDefaultWorkspace(user: SessionUser): Workspace | null {
  const accessible = getAccessibleWorkspaces(user);
  if (accessible.length === 0) return null;
  const home = HOME_BY_ROLE[user.role];
  if (home && accessible.includes(home)) return home;
  return accessible.includes('admin') ? 'admin' : accessible[0];
}

export function workspaceForPath(pathname: string): Workspace | null {
  return WORKSPACE_ORDER.find((ws) => pathname === WORKSPACE_PATHS[ws] || pathname.startsWith(WORKSPACE_PATHS[ws] + '/')) ?? null;
}
