package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.Session;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.security.JwtProperties;
import com.petcare.platform.security.JwtTokenService;
import com.petcare.platform.security.TokenClaims;

/** docs/adr/0003 D1, D4, D5; BR-QT-13 (chốt hạn phiên lúc tạo), BR-TK-11, 13, 14 (hủy phiên). */
class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00.987654Z");
    private static final Instant NOW_SECONDS = Instant.parse("2026-10-06T03:00:00Z");
    private static final long ACCOUNT = 5L;

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final Clock clock = Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE);
    private final JwtTokenService tokens = new JwtTokenService(
            new JwtProperties("unit-test-secret-key-for-hs256-signing-0123456789"), clock);
    private final SessionService service = new SessionService(sessions, configs, tokens, clock);

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.SESSION_TTL_HOURS)).thenReturn(12);
        when(sessions.save(any(Session.class))).thenAnswer(invocation -> {
            Session session = invocation.getArgument(0);
            ReflectionTestUtils.setField(session, "id", 77L);
            return session;
        });
    }

    @Test
    void openStoresHashOfJtiAndSnapshotsTtl() {
        SessionService.OpenedSession opened = service.open(ACCOUNT, "203.0.113.5", "Mozilla/5.0");

        Session saved = savedSession();
        assertThat(saved.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW_SECONDS.plusSeconds(12 * 3600));
        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(opened.sessionId()).isEqualTo(77L);
        assertThat(opened.expiresAt()).isEqualTo(saved.getExpiresAt());

        TokenClaims claims = tokens.parse(opened.accessToken()).orElseThrow();
        assertThat(claims.accountId()).isEqualTo(ACCOUNT);
        assertThat(claims.sessionId()).isEqualTo(77L);
        assertThat(saved.getTokenHash()).hasSize(64).isEqualTo(JtiHasher.hash(claims.jti()))
                .doesNotContain(claims.jti());
        assertThat(opened.accessToken()).doesNotContain(saved.getTokenHash());
    }

    @Test
    void ttlIsReadAtOpenTimeSoLaterChangesDoNotAffectOpenSessions() {
        service.open(ACCOUNT, null, null);
        Instant first = savedSession().getExpiresAt();

        when(configs.getInt(ConfigKey.SESSION_TTL_HOURS)).thenReturn(1);

        // Phiên đã mở giữ expires_at đã chốt; chỉ phiên mở sau dùng giá trị mới (BR-QT-13)
        assertThat(first).isEqualTo(NOW_SECONDS.plusSeconds(12 * 3600));
        SessionService.OpenedSession second = service.open(ACCOUNT, null, null);
        assertThat(second.expiresAt()).isEqualTo(NOW_SECONDS.plusSeconds(3600));
    }

    @Test
    void eachSessionGetsADistinctJti() {
        String a = tokens.parse(service.open(ACCOUNT, null, null).accessToken()).orElseThrow().jti();
        String b = tokens.parse(service.open(ACCOUNT, null, null).accessToken()).orElseThrow().jti();

        assertThat(a).isNotEqualTo(b).hasSize(22);
    }

    @Test
    void ipAndUserAgentAreTruncatedToColumnLength() {
        service.open(ACCOUNT, "x".repeat(60), "y".repeat(600));

        Session saved = savedSession();
        assertThat(saved.getIpAddress()).hasSize(SessionService.IP_MAX_LENGTH);
        assertThat(saved.getUserAgent()).hasSize(SessionService.USER_AGENT_MAX_LENGTH);
    }

    @Test
    void revokeCurrentSession() {
        service.revoke(77L);

        verify(sessions).revokeById(77L, NOW);
    }

    @Test
    void revokeAllSessionsOfAccount() {
        service.revokeAll(ACCOUNT);

        verify(sessions).revokeAllByAccount(ACCOUNT, NOW);
    }

    @Test
    void revokeOtherSessionsKeepsCurrent() {
        service.revokeOthers(ACCOUNT, 77L);

        verify(sessions).revokeAllByAccountExcept(ACCOUNT, 77L, NOW);
    }

    private Session savedSession() {
        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessions, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
