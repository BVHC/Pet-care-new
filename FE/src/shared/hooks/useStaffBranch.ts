// Chi nhánh của nhân viên đang đăng nhập. BE thật không trả `branchId` ở `POST /auth/login` (chỉ ở `GET /me` → `staffProfile.branchId`), mock thì có sẵn;
// `login` đã gộp `branchId` vào phiên (session.store.ts), còn phiên cũ lưu trước đó thì hỏi `GET /me` một lần khi chưa có.
import { useQuery } from '@tanstack/react-query';
import { profileApi } from '../api/profile.api';
import { useAccount } from '../stores/session.store';

/** `isPending` = đang hỏi `GET /me` để biết chi nhánh: trang đừng báo "không có chi nhánh" lúc này. */
export function useStaffBranch(): { branchId: number | null; isPending: boolean } {
  const account = useAccount();
  const known = account?.branchId ?? null;
  const me = useQuery({ queryKey: ['me'], queryFn: profileApi.me, enabled: account != null && known === null });
  return { branchId: known ?? me.data?.staffProfile?.branchId ?? null, isPending: known === null && me.isPending && me.fetchStatus !== 'idle' };
}

export function useStaffBranchId(): number | null {
  return useStaffBranch().branchId;
}
