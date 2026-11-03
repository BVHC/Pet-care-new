package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.extern.slf4j.Slf4j;

/**
 * UC03 — đăng xuất ({@code POST /api/auth/logout}, docs/adr/0003, docs/adr/0021): hủy phiên hiện tại. Nhân viên tự
 * offline ngay (BR-TN-06) vì trạng thái online đọc từ phiên chưa hủy (docs/adr/0091), nên không còn câu xóa
 * {@code last_seen_at}. Một transaction, một câu {@link SessionService#revoke}; refresh token của phiên không dùng được
 * nữa vì {@code RefreshTokenService} kiểm phiên chưa hủy. Không ghi audit (docs/adr/0019 mục 6). Phiên đã bị hủy giữa
 * filter và đây (khóa tài khoản đồng thời): hủy lại 0 dòng, vẫn thành công — trạng thái cuối như nhau.
 */
@Slf4j
@Service
public class LogoutService {

    private final SessionService sessions;
    private final BranchScope branchScope;

    public LogoutService(SessionService sessions, BranchScope branchScope) {
        this.sessions = sessions;
        this.branchScope = branchScope;
    }

    @Transactional
    public void logout() {
        SecurityPrincipal principal = branchScope.current();
        sessions.revoke(principal.sessionId());
        log.info("LOGOUT accountId={} sessionId={}", principal.accountId(), principal.sessionId());
    }
}
