package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.RefreshTokenResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.RefreshToken;
import com.petcare.module.identity.exception.InvalidRefreshTokenException;
import com.petcare.module.identity.repository.RefreshTokenRepository;
import com.petcare.module.identity.repository.SessionAuthView;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.security.JwtProperties;
import com.petcare.platform.security.JwtTokenService;
import com.petcare.platform.security.TokenClaims;

/**
 * docs/adr/0091: làm mới access token. Thứ tự khóa → đọc → guard → ghi (rotateAccess trước mọi ghi khác), mọi nhánh từ
 * chối ném 401 chung <b>trước</b> mọi lệnh ghi, riêng nhánh dùng lại token đã dùng hủy phiên + audit.
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00.987654Z");
    private static final Instant NOW_SECONDS = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant SESSION_EXPIRES = NOW_SECONDS.plus(Duration.ofHours(10));
    private static final long ACCOUNT = 5L;
    private static final long SESSION = 9L;
    private static final long TOKEN_ID = 31L;
    private static final String PRESENTED = "presented-refresh-token";

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE);
    private final JwtTokenService tokens = new JwtTokenService(
            new JwtProperties("unit-test-secret-key-for-hs256-signing-0123456789"), clock);
    private final SessionService sessionService = new SessionService(sessions, refreshTokens, configs, tokens, clock);
    private final RefreshTokenService service = new RefreshTokenService(refreshTokens, sessions, sessionService,
            tokens, audit, clock);

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.SESSION_ACCESS_TTL_MINUTES)).thenReturn(10);
        when(sessions.rotateAccess(anyLong(), anyString(), any(), any())).thenReturn(1);
        when(refreshTokens.markUsed(anyLong(), any())).thenReturn(1);
        given(token(null, SESSION_EXPIRES), view(SESSION_EXPIRES, null, AccountStatus.ACTIVE, false));
    }

    @Test
    void validRefreshRotatesBothTokensInTheDocumentedOrder() {
        RefreshTokenResponse response = service.refresh(PRESENTED);

        InOrder order = inOrder(refreshTokens, sessions);
        order.verify(refreshTokens).findByTokenHashForUpdate(JtiHasher.hash(PRESENTED));
        order.verify(sessions).findAuthView(SESSION);
        ArgumentCaptor<String> newJtiHash = ArgumentCaptor.forClass(String.class);
        order.verify(sessions).rotateAccess(eq(SESSION), newJtiHash.capture(), eq(NOW_SECONDS.plusSeconds(600)),
                eq(NOW_SECONDS));
        order.verify(refreshTokens).markUsed(TOKEN_ID, NOW_SECONDS);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        order.verify(refreshTokens).saveAndFlush(saved.capture());

        TokenClaims claims = tokens.parse(response.accessToken()).orElseThrow();
        assertThat(claims.accountId()).isEqualTo(ACCOUNT);
        assertThat(claims.sessionId()).isEqualTo(SESSION);
        assertThat(JtiHasher.hash(claims.jti())).isEqualTo(newJtiHash.getValue());
        assertThat(response.accessTokenExpiresAt()).isEqualTo(NOW_SECONDS.plusSeconds(600));
        assertThat(response.expiresAt()).isEqualTo(SESSION_EXPIRES);
        assertThat(response.refreshToken()).hasSize(43).isNotEqualTo(PRESENTED);
        assertThat(saved.getValue().getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(JtiHasher.hash(response.refreshToken()))
                .hasSize(64);
        assertThat(saved.getValue().getExpiresAt()).as("hạn refresh token = hạn phiên").isEqualTo(SESSION_EXPIRES);
        assertThat(saved.getValue().isUsed()).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void accessExpiryNeverExceedsTheSessionExpiry() {
        Instant soon = NOW_SECONDS.plusSeconds(120);
        given(token(null, soon), view(soon, null, AccountStatus.ACTIVE, false));

        RefreshTokenResponse response = service.refresh(PRESENTED);

        assertThat(response.accessTokenExpiresAt()).isEqualTo(soon);
        assertThat(response.expiresAt()).isEqualTo(soon);
    }

    @Test
    void unknownTokenIsRejectedWithoutAnyWrite() {
        when(refreshTokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertRejected();

        verifyNoWrites();
        verifyNoInteractions(audit);
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutAnyWrite() {
        given(token(null, NOW_SECONDS), view(SESSION_EXPIRES, null, AccountStatus.ACTIVE, false));

        assertRejected();

        verifyNoWrites();
    }

    @Test
    void revokedSessionIsRejectedWithoutAnyWrite() {
        given(token(null, SESSION_EXPIRES), view(SESSION_EXPIRES, NOW_SECONDS.minusSeconds(1), AccountStatus.ACTIVE,
                false));

        assertRejected();

        verifyNoWrites();
    }

    @Test
    void expiredSessionIsRejectedWithoutAnyWrite() {
        given(token(null, SESSION_EXPIRES), view(NOW_SECONDS, null, AccountStatus.ACTIVE, false));

        assertRejected();

        verifyNoWrites();
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING", "DISABLED"})
    void inactiveAccountIsRejectedWithoutAnyWrite(AccountStatus status) {
        given(token(null, SESSION_EXPIRES), view(SESSION_EXPIRES, null, status, false));

        assertRejected();

        verifyNoWrites();
    }

    @Test
    void lockedAccountIsRejectedWithoutAnyWrite() {
        given(token(null, SESSION_EXPIRES), view(SESSION_EXPIRES, null, AccountStatus.ACTIVE, true));

        assertRejected();

        verifyNoWrites();
    }

    @Test
    void sessionRevokedBetweenReadAndWriteRollsBackBeforeTouchingTheTokens() {
        when(sessions.rotateAccess(anyLong(), anyString(), any(), any())).thenReturn(0);

        assertRejected();

        verify(refreshTokens, never()).markUsed(anyLong(), any());
        verify(refreshTokens, never()).saveAndFlush(any());
    }

    @Test
    void reusedTokenRevokesTheLiveSessionAuditsAndIsRejected() {
        given(token(NOW_SECONDS.minusSeconds(30), SESSION_EXPIRES),
                view(SESSION_EXPIRES, null, AccountStatus.ACTIVE, false));

        assertRejected();

        InOrder order = inOrder(sessions, audit);
        order.verify(sessions).revokeById(SESSION, NOW_SECONDS);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        order.verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(IdentityAuditActions.REFRESH_TOKEN_REUSED);
        assertThat(entry.getValue().entityType()).isEqualTo("sessions");
        assertThat(entry.getValue().entityId()).isEqualTo(SESSION);
        assertThat(entry.getValue().actorAccountId()).isEqualTo(ACCOUNT);
        assertThat(entry.getValue().actorEmail()).isEqualTo("vet@petcare.test");
        verifyNoWrites();
    }

    @Test
    void reusedTokenOfAnAlreadyRevokedSessionIsOnlyRejected() {
        given(token(NOW_SECONDS.minusSeconds(30), SESSION_EXPIRES),
                view(SESSION_EXPIRES, NOW_SECONDS.minusSeconds(5), AccountStatus.ACTIVE, false));

        assertRejected();

        verify(sessions, never()).revokeById(anyLong(), any());
        verifyNoInteractions(audit);
        verifyNoWrites();
    }

    @Test
    void rejectionIsTheGenericUnauthenticatedError() {
        when(refreshTokens.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(PRESENTED)).isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage(InvalidRefreshTokenException.MESSAGE)
                .satisfies(ex -> assertThat(((InvalidRefreshTokenException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    private void assertRejected() {
        assertThatThrownBy(() -> service.refresh(PRESENTED)).isInstanceOf(InvalidRefreshTokenException.class);
    }

    private void verifyNoWrites() {
        verify(sessions, never()).rotateAccess(anyLong(), anyString(), any(), any());
        verify(refreshTokens, never()).markUsed(anyLong(), any());
        verify(refreshTokens, never()).saveAndFlush(any());
    }

    private void given(RefreshToken token, SessionAuthView view) {
        when(refreshTokens.findByTokenHashForUpdate(JtiHasher.hash(PRESENTED))).thenReturn(Optional.of(token));
        when(sessions.findAuthView(SESSION)).thenReturn(Optional.of(view));
    }

    private static RefreshToken token(Instant usedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken(SESSION, JtiHasher.hash(PRESENTED), expiresAt);
        ReflectionTestUtils.setField(token, "id", TOKEN_ID);
        ReflectionTestUtils.setField(token, "usedAt", usedAt);
        return token;
    }

    private static SessionAuthView view(Instant expiresAt, Instant revokedAt, AccountStatus status, boolean locked) {
        return new SessionAuthView(SESSION, ACCOUNT, JtiHasher.hash("old-jti"), expiresAt, revokedAt,
                "vet@petcare.test", Role.VET, status, locked, false, 3L);
    }
}
