import { useAdminSession } from '../stores/admin-session.store';
import type { SessionUser } from '../types/admin';

/** Signed-in staff member; workspace routes are guarded, so a missing session is a bug. */
export function useStaffUser(): SessionUser {
  const user = useAdminSession((s) => s.user);
  if (!user) throw new Error('Staff workspace rendered without a session');
  return user;
}
