package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.SessionAuthView;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.security.SessionAuthenticator;
import com.petcare.platform.security.TokenClaims;

/**
 * Kiểm tra phiên ở mỗi request (docs/adr/0003). Hợp lệ khi: phiên tồn tại, đúng tài khoản, khớp {@code jti}, chưa hủy,
 * chưa hết hạn, tài khoản {@code ACTIVE} và không bị khóa. Đọc {@code status}/{@code is_locked} trực tiếp nên khóa,
 * vô hiệu hóa có hiệu lực ngay (BR-TK-11, BR-QT-11) kể cả trước khi phiên bị hủy. Khóa tạm do đăng nhập sai
 * ({@code locked_until}, BR-TK-09) chỉ chặn đăng nhập, không chặn phiên đang có.
 * <p>
 * Chỉ đọc: trạng thái online của nhân viên không còn được ghi ở mỗi request mà suy ra từ hạn access token của phiên
 * (BR-TN-06, docs/adr/0091, trả D024), nên request poll tự động không làm ai "online".
 */
@Service
public class SessionAuthenticationService implements SessionAuthenticator {

    private final SessionRepository sessions;
    private final Clock clock;

    public SessionAuthenticationService(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SecurityPrincipal> authenticate(TokenClaims claims) {
        SessionAuthView view = sessions.findAuthView(claims.sessionId()).orElse(null);
        Instant now = Instant.now(clock);
        if (view == null || !isValid(view, claims, now)) {
            return Optional.empty();
        }
        return Optional.of(new AccountPrincipal(view.accountId(), view.email(), view.sessionId(), view.role(),
                view.branchId(), view.mustChangePassword()));
    }

    private static boolean isValid(SessionAuthView view, TokenClaims claims, Instant now) {
        return view.accountId().equals(claims.accountId())
                && JtiHasher.matches(claims.jti(), view.tokenHash())
                && view.revokedAt() == null
                && view.expiresAt().isAfter(now)
                && view.status() == AccountStatus.ACTIVE
                && !view.locked();
    }
}
