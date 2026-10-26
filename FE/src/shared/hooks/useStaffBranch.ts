// Chi nhánh của nhân viên đang đăng nhập. BE thật không trả `branchId` ở `POST /auth/login` (chỉ ở `GET /me` → `staffProfile.branchId`), mock thì có sẵn;
// chỉ hỏi `GET /me` khi phiên chưa có.
import { useQuery } from '@tanstack/react-query';
import { profileApi } from '../api/profile.api';
import { useAccount } from '../stores/session.store';

export function useStaffBranchId(): number | null {
  const account = useAccount();
  const known = account?.branchId ?? null;
  const me = useQuery({ queryKey: ['me'], queryFn: profileApi.me, enabled: account != null && known === null });
  return known ?? me.data?.staffProfile?.branchId ?? null;
}
