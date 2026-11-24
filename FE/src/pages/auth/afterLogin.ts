import { homePathFor } from '../../shared/constants/workspaces';
import { useSession } from '../../shared/stores/session.store';
import type { AccountSummary } from '../../shared/types/auth';

/** Trang sau khi đăng nhập (mật khẩu hoặc Google). BR-TK-19: còn cờ chờ quyết định liên kết thì nhắc lại ở mỗi lần đăng nhập. */
export function afterLoginPath(account: AccountSummary, from?: string): string {
  return useSession.getState().linkDecisionPending ? '/account/link' : (from ?? homePathFor(account.role));
}
