package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.extern.slf4j.Slf4j;

/**
 * UC03 — đăng xuất ({@code POST /api/auth/logout}, docs/adr/0003, docs/adr/0021): hủy phiên hiện tại và đưa người
 * dùng về offline ngay (BR-TN-06). Một transaction, hai câu chạy theo thứ tự cố định (docs/adr/0021 mục 1, 2):
 * <ol>
 *   <li>{@link AccountRepository#clearLastSeen} — khóa dòng {@code accounts} trước, giữ tới lúc commit;</li>
 *   <li>{@link SessionService#revoke} — rồi mới tới dòng {@code sessions}.</li>
 * </ol>
 * Thứ tự {@code accounts → sessions} giống mọi luồng hủy phiên (đổi / đặt lại mật khẩu, khóa, vô hiệu hóa) nên không
 * có vòng chờ khóa, và đóng race với {@link AccountRepository#touchLastSeen} của request khác cùng token. Cả hai câu là
 * {@code @Modifying} query chạy ngay tại chỗ gọi; không nạp entity {@code Account} (setter sẽ bị hoãn tới flush và đảo
 * thứ tự). Không ghi audit (docs/adr/0019 mục 6). Phiên đã bị hủy giữa filter và đây (khóa tài khoản đồng thời): hủy
 * lại 0 dòng, vẫn thành công — trạng thái cuối như nhau.
 */
@Slf4j
@Service
public class LogoutService {

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final BranchScope branchScope;

    public LogoutService(AccountRepository accounts, SessionService sessions, BranchScope branchScope) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.branchScope = branchScope;
    }

    @Transactional
    public void logout() {
        SecurityPrincipal principal = branchScope.current();
        accounts.clearLastSeen(principal.accountId());
        sessions.revoke(principal.sessionId());
        log.info("LOGOUT accountId={} sessionId={}", principal.accountId(), principal.sessionId());
    }
}
