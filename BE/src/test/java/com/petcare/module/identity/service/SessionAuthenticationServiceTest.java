package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.SessionAuthView;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.security.TokenClaims;

/**
 * docs/adr/0003 D2, D3, D10. Mỗi điều kiện của phiên hợp lệ có một ca vi phạm; BR-TK-11, BR-QT-11, 12 (khóa, vô hiệu
 * hóa), BR-TK-09 (khóa tạm không chặn phiên), BR-TN-06 (last_seen_at của nhân viên).
 */
class SessionAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
    private static final long ACCOUNT = 5L;
    private static final long SESSION = 9L;
    private static final String JTI = "jti-value";

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionAuthenticationService service = new SessionAuthenticationService(sessions, accounts,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private static final TokenClaims CLAIMS = new TokenClaims(ACCOUNT, SESSION, JTI);

    /** Phiên hợp lệ của một VET chi nhánh 3; từng test đổi đúng một trường. */
    private static ViewBuilder valid() {
        return new ViewBuilder();
    }

    @Test
    void validSessionReturnsPrincipalFromDatabase() {
        given(valid());

        Optional<SecurityPrincipal> principal = service.authenticate(CLAIMS);

        assertThat(principal).get().isEqualTo(
                new AccountPrincipal(ACCOUNT, "vet@petcare.test", SESSION, Role.VET, 3L, false));
    }

    @Test
    void missingSessionRejected() {
        when(sessions.findAuthView(SESSION)).thenReturn(Optional.empty());

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void sessionOfAnotherAccountRejected() {
        given(valid().accountId(ACCOUNT + 1));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void jtiMismatchRejected() {
        given(valid().tokenHash(JtiHasher.hash("other-jti")));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void revokedSessionRejected() {
        given(valid().revokedAt(NOW.minusSeconds(1)));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void sessionExpiringExactlyNowRejected() {
        given(valid().expiresAt(NOW));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void sessionExpiringOneSecondLaterAccepted() {
        given(valid().expiresAt(NOW.plusSeconds(1)));

        assertThat(service.authenticate(CLAIMS)).isPresent();
    }

    @Test
    void pendingAccountRejected() {
        given(valid().status(AccountStatus.PENDING));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void disabledAccountRejected() {
        given(valid().status(AccountStatus.DISABLED));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void lockedAccountRejected() {
        given(valid().locked(true));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void disabledUnlockedAccountStillRejected() {
        // BR-QT-12: mở khóa trả về trạng thái trước khóa — DISABLED vẫn không truy cập được
        given(valid().status(AccountStatus.DISABLED).locked(false));

        assertThat(service.authenticate(CLAIMS)).isEmpty();
    }

    @Test
    void temporaryLoginLockDoesNotEndExistingSession() {
        // BR-TK-09 chỉ chặn đăng nhập; locked_until không có trong điều kiện phiên. View không mang locked_until,
        // nên tài khoản đang khóa tạm vẫn là ACTIVE + is_locked=false và phiên hợp lệ.
        given(valid());

        assertThat(service.authenticate(CLAIMS)).isPresent();
    }

    @Test
    void mustChangePasswordIsCarriedToPrincipal() {
        given(valid().mustChangePassword(true));

        assertThat(service.authenticate(CLAIMS)).get()
                .extracting(SecurityPrincipal::mustChangePassword).isEqualTo(true);
    }

    @Test
    void adminHasNoBranchAndIsNotBranchScoped() {
        given(valid().role(Role.ADMIN).branchId(null));

        SecurityPrincipal principal = service.authenticate(CLAIMS).orElseThrow();
        assertThat(principal.branchId()).isNull();
        assertThat(principal.branchScoped()).isFalse();
    }

    @Test
    void staffLastSeenTouchedWhenOlderThanThrottle() {
        given(valid().lastSeenAt(NOW.minusSeconds(61)));

        service.authenticate(CLAIMS);

        verify(accounts).touchLastSeen(ACCOUNT, SESSION, NOW, NOW.minusSeconds(60));
    }

    @Test
    void staffLastSeenTouchedWhenNeverSeen() {
        given(valid().lastSeenAt(null));

        service.authenticate(CLAIMS);

        verify(accounts).touchLastSeen(ACCOUNT, SESSION, NOW, NOW.minusSeconds(60));
    }

    @Test
    void staffLastSeenNotTouchedWithinThrottle() {
        given(valid().lastSeenAt(NOW.minusSeconds(60)));

        service.authenticate(CLAIMS);

        verify(accounts, never()).touchLastSeen(anyLong(), anyLong(), any(), any());
    }

    @Test
    void customerLastSeenNeverTouched() {
        given(valid().role(Role.CUSTOMER).branchId(null).lastSeenAt(null));

        service.authenticate(CLAIMS);

        verify(accounts, never()).touchLastSeen(anyLong(), anyLong(), any(), any());
    }

    @Test
    void rejectedSessionDoesNotTouchLastSeen() {
        given(valid().locked(true).lastSeenAt(null));

        service.authenticate(CLAIMS);

        verify(accounts, never()).touchLastSeen(anyLong(), anyLong(), any(), any());
    }

    private void given(ViewBuilder builder) {
        when(sessions.findAuthView(SESSION)).thenReturn(Optional.of(builder.build()));
    }

    private static final class ViewBuilder {
        private Long accountId = ACCOUNT;
        private String tokenHash = JtiHasher.hash(JTI);
        private Instant expiresAt = NOW.plusSeconds(3600);
        private Instant revokedAt;
        private Role role = Role.VET;
        private AccountStatus status = AccountStatus.ACTIVE;
        private boolean locked;
        private boolean mustChangePassword;
        private Instant lastSeenAt = NOW.minusSeconds(5);
        private Long branchId = 3L;

        ViewBuilder accountId(Long value) { accountId = value; return this; }
        ViewBuilder tokenHash(String value) { tokenHash = value; return this; }
        ViewBuilder expiresAt(Instant value) { expiresAt = value; return this; }
        ViewBuilder revokedAt(Instant value) { revokedAt = value; return this; }
        ViewBuilder role(Role value) { role = value; return this; }
        ViewBuilder status(AccountStatus value) { status = value; return this; }
        ViewBuilder locked(boolean value) { locked = value; return this; }
        ViewBuilder mustChangePassword(boolean value) { mustChangePassword = value; return this; }
        ViewBuilder lastSeenAt(Instant value) { lastSeenAt = value; return this; }
        ViewBuilder branchId(Long value) { branchId = value; return this; }

        SessionAuthView build() {
            return new SessionAuthView(SESSION, accountId, tokenHash, expiresAt, revokedAt, "vet@petcare.test",
                    role, status, locked, mustChangePassword, lastSeenAt, branchId);
        }
    }
}
