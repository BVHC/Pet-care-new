// Role types for admin
export type Role =
  | 'SUPER_ADMIN'
  | 'ORG_ADMIN'
  | 'STORE_MANAGER'
  | 'RECEPTIONIST'
  | 'VETERINARIAN'
  | 'GROOMER'
  | 'INVENTORY_STAFF'
  | 'FINANCE_STAFF'
  | 'CUSTOMER';

// Alias for UserRole
export type UserRole = Role;

export interface SessionUser {
  id: string;
  email: string;
  name: string;
  /** Primary role — decides the default workspace. */
  role: Role;
  /** Every role held (multi-role staff, e.g. a vet who also staffs the desk). */
  roles?: Role[];
  organizationId: string;
  storeId?: string;
}

// Demo users for login
export const DEMO_USERS = [
  { id: '1', email: 'admin@petcare.vn', name: 'Super Admin', role: 'SUPER_ADMIN' as Role, organizationId: 'org-1' },
  { id: '2', email: 'orgadmin@petcare.vn', name: 'Org Admin', role: 'ORG_ADMIN' as Role, organizationId: 'org-1' },
  { id: '3', email: 'manager@store1.vn', name: 'Hoàng Nam', role: 'STORE_MANAGER' as Role, organizationId: 'org-1', storeId: 'store-1' },
  { id: '4', email: 'reception@store1.vn', name: 'Lan Chi', role: 'RECEPTIONIST' as Role, organizationId: 'org-1', storeId: 'store-1' },
  { id: '5', email: 'doctor@store1.vn', name: 'BS. Minh Anh', role: 'VETERINARIAN' as Role, roles: ['VETERINARIAN', 'RECEPTIONIST'] as Role[], organizationId: 'org-1', storeId: 'store-1' },
  { id: '6', email: 'groomer@store1.vn', name: 'Thu Hà', role: 'GROOMER' as Role, organizationId: 'org-1', storeId: 'store-1' },
  { id: '7', email: 'inventory@store1.vn', name: 'Inventory Staff', role: 'INVENTORY_STAFF' as Role, organizationId: 'org-1', storeId: 'store-1' },
  { id: '8', email: 'finance@org1.vn', name: 'Finance Staff', role: 'FINANCE_STAFF' as Role, organizationId: 'org-1' },
];

export const ROLE_LABELS: Record<Role, string> = {
  SUPER_ADMIN: 'Super Admin',
  ORG_ADMIN: 'Quản trị viên',
  STORE_MANAGER: 'Quản lý cửa hàng',
  RECEPTIONIST: 'Lễ tân',
  VETERINARIAN: 'Bác sĩ thú y',
  GROOMER: 'Nhân viên grooming',
  INVENTORY_STAFF: 'Nhân viên kho',
  FINANCE_STAFF: 'Nhân viên tài chính',
  CUSTOMER: 'Khách hàng',
};

export const ALL_STAFF_ROLES: Role[] = [
  'SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST',
  'VETERINARIAN', 'GROOMER', 'INVENTORY_STAFF', 'FINANCE_STAFF',
];

export function isStaff(role: Role | undefined): boolean {
  return !!role && ALL_STAFF_ROLES.includes(role);
}
