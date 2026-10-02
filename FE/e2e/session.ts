import type { Page } from '@playwright/test';

type Role = 'RECEPTIONIST' | 'VETERINARIAN' | 'GROOMER' | 'STORE_MANAGER';

interface DemoUser {
  id: string;
  email: string;
  name: string;
  role: Role;
  roles?: Role[];
  organizationId: string;
  storeId?: string;
}

// Same accounts as DEMO_USERS in src/shared/types/admin.ts.
export const DESK: DemoUser = { id: '4', email: 'reception@store1.vn', name: 'Lan Chi', role: 'RECEPTIONIST', organizationId: 'org-1', storeId: 'store-1' };
export const VET_AT_DESK: DemoUser = {
  id: '5',
  email: 'doctor@store1.vn',
  name: 'BS. Minh Anh',
  role: 'VETERINARIAN',
  roles: ['VETERINARIAN', 'RECEPTIONIST'],
  organizationId: 'org-1',
  storeId: 'store-1',
};
export const GROOMER: DemoUser = { id: '6', email: 'groomer@store1.vn', name: 'Thu Hà', role: 'GROOMER', organizationId: 'org-1', storeId: 'store-1' };
export const MANAGER: DemoUser = { id: '3', email: 'manager@store1.vn', name: 'Hoàng Nam', role: 'STORE_MANAGER', organizationId: 'org-1', storeId: 'store-1' };

/** Signs in through the persisted session store. Each test has a fresh context, so a freshly seeded clinic. */
export async function signIn(page: Page, user: DemoUser) {
  await page.addInitScript((u) => {
    if (!localStorage.getItem('admin-session')) {
      localStorage.setItem('admin-session', JSON.stringify({ state: { user: u, isAuthenticated: true }, version: 0 }));
    }
  }, user);
}
