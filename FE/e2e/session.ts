import type { Page } from '@playwright/test';

type Role = 'RECEPTIONIST' | 'VET' | 'CARETAKER' | 'BRANCH_MANAGER';

interface DemoAccount {
  id: number;
  email: string;
  fullName: string;
  role: Role;
}

// Same accounts as MOCK_ACCOUNTS in src/shared/api/mock/identity.mock.ts (`npm run dev` runs the contract mock).
// Ids 3–6 are also the staff ids of clinic-db, so "assigned to me" works.
export const MANAGER: DemoAccount = { id: 3, email: 'manager@store1.vn', fullName: 'Hoàng Nam', role: 'BRANCH_MANAGER' };
export const DESK: DemoAccount = { id: 4, email: 'reception@store1.vn', fullName: 'Lan Chi', role: 'RECEPTIONIST' };
export const VET: DemoAccount = { id: 5, email: 'doctor@store1.vn', fullName: 'BS. Minh Anh', role: 'VET' };
export const CARETAKER: DemoAccount = { id: 6, email: 'caretaker@store1.vn', fullName: 'Thu Hà', role: 'CARETAKER' };

/** What session.store persists under `petcare-session`, with a token the mock accepts. */
function persistedSession(a: DemoAccount): string {
  return JSON.stringify({
    state: {
      token: `mock-token-${a.id}`,
      expiresAt: new Date(Date.now() + 12 * 3_600_000).toISOString(),
      account: { id: a.id, email: a.email, role: a.role, status: 'ACTIVE', isLocked: false, mustChangePassword: false },
      linkDecisionPending: false,
      fullName: a.fullName,
    },
    version: 0,
  });
}

/** Signs in through the persisted session store. Each test has a fresh context, so a freshly seeded clinic. */
export async function signIn(page: Page, account: DemoAccount) {
  await page.addInitScript((session) => {
    if (!localStorage.getItem('petcare-session')) localStorage.setItem('petcare-session', session);
  }, persistedSession(account));
}

/** One role per account (v16): hand a visit to the next person by signing in as them. */
export async function switchTo(page: Page, account: DemoAccount, path: string) {
  if (page.url() === 'about:blank') await page.goto('/');
  await page.evaluate((session) => localStorage.setItem('petcare-session', session), persistedSession(account));
  await page.goto(path);
}
