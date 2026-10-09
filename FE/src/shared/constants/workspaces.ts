import type { Role } from '../types/auth';

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

/** Theo actor của UC (01-business-operations): ADMIN (A03) không làm nghiệp vụ phòng khám. */
export const WORKSPACE_ROLES: Record<Workspace, readonly Role[]> = {
  reception: ['RECEPTIONIST', 'BRANCH_MANAGER'], // UC44–45, UC66, UC70; A05 gán lại lượt (UC45)
  doctor: ['VET'], // UC48, UC49
  grooming: ['CARETAKER'], // UC52
  admin: ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'],
};

const HOME_BY_ROLE: Partial<Record<Role, Workspace>> = {
  RECEPTIONIST: 'reception',
  VET: 'doctor',
  CARETAKER: 'grooming',
};

export function canAccessWorkspace(role: Role, workspace: Workspace): boolean {
  return WORKSPACE_ROLES[workspace].includes(role);
}

export function getAccessibleWorkspaces(role: Role): Workspace[] {
  return WORKSPACE_ORDER.filter((ws) => canAccessWorkspace(role, ws));
}

/** Trang đầu tiên sau khi đăng nhập. */
export function homePathFor(role: Role): string {
  if (role === 'CUSTOMER') return '/';
  if (role === 'ADMIN') return '/admin/users';
  return WORKSPACE_PATHS[HOME_BY_ROLE[role] ?? 'admin'];
}

export function workspaceForPath(pathname: string): Workspace | null {
  return WORKSPACE_ORDER.find((ws) => pathname === WORKSPACE_PATHS[ws] || pathname.startsWith(WORKSPACE_PATHS[ws] + '/')) ?? null;
}
