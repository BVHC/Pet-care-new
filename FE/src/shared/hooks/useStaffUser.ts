import { useSessionUser } from '../stores/session.store';
import type { SessionUser } from '../types/auth';

/** Signed-in staff member; workspace routes are guarded, so a missing session is a bug. */
export function useStaffUser(): SessionUser {
  const user = useSessionUser();
  if (!user) throw new Error('Staff workspace rendered without a session');
  return user;
}
