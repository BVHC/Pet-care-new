package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
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
 */
@Service
public class SessionAuthenticationService implements SessionAuthenticator {

    /** BR-TN-06: ghi {@code last_seen_at} tối đa một lần mỗi 60 giây cho mỗi nhân viên. */
    static final Duration LAST_SEEN_THROTTLE = Duration.ofSeconds(60);

    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final Clock clock;

    public SessionAuthenticationService(SessionRepository sessions, AccountRepository accounts, Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<SecurityPrincipal> authenticate(TokenClaims claims) {
        SessionAuthView view = sessions.findAuthView(claims.sessionId()).orElse(null);
        Instant now = Instant.now(clock);
        if (view == null || !isValid(view, claims, now)) {
            return Optional.empty();
        }
        if (view.role() != Role.CUSTOMER) {
            touchLastSeen(view, now);
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

    private void touchLastSeen(SessionAuthView view, Instant now) {
        Instant threshold = now.minus(LAST_SEEN_THROTTLE);
        if (view.lastSeenAt() == null || view.lastSeenAt().isBefore(threshold)) {
            accounts.touchLastSeen(view.accountId(), view.sessionId(), now, threshold);
        }
    }
}
