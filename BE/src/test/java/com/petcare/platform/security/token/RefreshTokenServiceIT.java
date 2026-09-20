package com.petcare.platform.security.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test cho RefreshTokenService trên Postgres thật (Testcontainers
 * — docs/convention/backend/09-testing.md: "Integration test: @SpringBootTest
 * + Testcontainers"). Container tự chạy Flyway migration thật (V1/V2/V3) nên
 * tự chứa hoàn toàn, không phụ thuộc DB dựng sẵn bên ngoài. Không có Account
 * JPA entity nên account giả lập bằng INSERT SQL trực tiếp qua JdbcTemplate.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class RefreshTokenServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUpAccount() {
        // accounts.email là NOT NULL từ khi RULE-01-10 đổi danh tính chính sang email
        // (2026-09-13) — accounts.phone giờ optional nhưng vẫn insert cho đủ dữ liệu test.
        accountId = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (email, phone, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                "test-" + System.nanoTime() + "@example.com",
                "09" + System.nanoTime() % 100_000_000L,
                "bcrypt-hash-placeholder");
    }

    @Test
    void issue_persistsHashedToken_notRawToken() {
        UUID jti = UUID.randomUUID();
        UUID accessJti = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);

        RefreshTokenEntity saved = refreshTokenService.issue(accountId, "raw-token-abc", jti, accessJti, expiresAt, "junit-agent", "127.0.0.1");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTokenHash()).isNotEqualTo("raw-token-abc");
        assertThat(saved.getAccountId()).isEqualTo(accountId);
        assertThat(saved.getAccessTokenJti()).isEqualTo(accessJti);
        assertThat(saved.isRevoked()).isFalse();
    }

    @Test
    void rotate_revokesOldToken_andLinksReplacedBy() {
        UUID oldJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "old-raw-token", oldJti, UUID.randomUUID(), Instant.now().plusSeconds(3600), null, null);

        UUID newJti = UUID.randomUUID();
        UUID newAccessJti = UUID.randomUUID();
        RefreshTokenEntity rotated = refreshTokenService.rotate("old-raw-token", "new-raw-token", newJti, newAccessJti, Instant.now().plusSeconds(3600));

        assertThat(rotated.isRevoked()).isFalse();
        assertThat(rotated.getAccessTokenJti()).isEqualTo(newAccessJti);

        List<RefreshTokenEntity> stillActive = refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId);
        assertThat(stillActive).hasSize(1);
        assertThat(stillActive.get(0).getJti()).isEqualTo(newJti);
    }

    @Test
    void rotate_rejectsAlreadyRevokedToken() {
        refreshTokenService.issue(accountId, "single-use-token", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(3600), null, null);
        boolean revoked = refreshTokenService.revoke("single-use-token", RefreshTokenRevokeReason.LOGOUT);
        assertThat(revoked).isTrue();

        assertThatThrownBy(() -> refreshTokenService.rotate("single-use-token", "another-token", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revoke_unknownToken_returnsFalse_doesNotThrow() {
        boolean revoked = refreshTokenService.revoke("never-issued-token", RefreshTokenRevokeReason.LOGOUT);
        assertThat(revoked).isFalse();
    }

    @Test
    void revokeByAccessTokenJti_findsAndRevokesLinkedRefreshToken() {
        // RULE-01-06: Logout tra ra đúng refresh token của phiên chỉ từ JTI access token,
        // không cần client gửi kèm raw refresh token.
        UUID accessJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "linked-token", UUID.randomUUID(), accessJti, Instant.now().plusSeconds(3600), null, null);

        boolean revoked = refreshTokenService.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isTrue();
        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    }

    @Test
    void revokeByAccessTokenJti_unknownJti_returnsFalse_doesNotThrow() {
        boolean revoked = refreshTokenService.revokeByAccessTokenJti(UUID.randomUUID(), RefreshTokenRevokeReason.LOGOUT);
        assertThat(revoked).isFalse();
    }

    @Test
    void revokeByAccessTokenJti_staleAccessTokenAfterRotation_stillRevokesLiveGeneration() {
        // Regression: client logout bằng access token CŨ HƠN lần rotate gần nhất của cùng
        // phiên (JWT chưa hết hạn nên server vẫn chấp nhận nó ở endpoint khác). Row gắn với
        // accessJti cũ đã bị revoke do ROTATED (revokedAt != null) nên
        // findByAccessTokenJtiAndRevokedAtIsNull không còn thấy nó — phải lần theo replacedBy
        // tới generation đang sống rồi revoke đúng row đó, nếu không refresh token thật sự
        // đang hoạt động sẽ không bao giờ bị đụng tới (vi phạm RULE-01-06).
        UUID staleAccessJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "stale-gen1-raw-token", UUID.randomUUID(), staleAccessJti,
                Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.rotate("stale-gen1-raw-token", "stale-gen2-raw-token", UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(3600));

        boolean revoked = refreshTokenService.revokeByAccessTokenJti(staleAccessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isTrue();
        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    }

    @Test
    void revokeByAccessTokenJti_multipleRotations_walksFullChain() {
        UUID firstAccessJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "gen-1", UUID.randomUUID(), firstAccessJti,
                Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.rotate("gen-1", "gen-2", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(3600));
        refreshTokenService.rotate("gen-2", "gen-3", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(3600));

        boolean revoked = refreshTokenService.revokeByAccessTokenJti(firstAccessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isTrue();
        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    }

    @Test
    void revokeByAccessTokenJti_sessionAlreadyRevokedForOtherReason_returnsFalse_doesNotThrow() {
        // Nếu chuỗi rotate dừng ở 1 row revoke vì lý do KHÁC ROTATED (vd LOCK_ACCOUNT), phiên
        // đã bị vô hiệu hoá từ trước rồi — không có gì để đi tiếp/thu hồi thêm.
        UUID staleAccessJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "locked-raw-token", UUID.randomUUID(), staleAccessJti,
                Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.revoke("locked-raw-token", RefreshTokenRevokeReason.LOCK_ACCOUNT);

        boolean revoked = refreshTokenService.revokeByAccessTokenJti(staleAccessJti, RefreshTokenRevokeReason.LOGOUT);

        assertThat(revoked).isFalse();
    }

    @Test
    void revokeAllForAccount_revokesEveryActiveToken() {
        UUID accessJti1 = UUID.randomUUID();
        UUID accessJti2 = UUID.randomUUID();
        refreshTokenService.issue(accountId, "token-1", UUID.randomUUID(), accessJti1, Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.issue(accountId, "token-2", UUID.randomUUID(), accessJti2, Instant.now().plusSeconds(3600), null, null);

        // RULE-02-04/07 — caller (TokenIssuanceFacadeImpl) cần list accessTokenJti này để
        // blacklist đúng access token đang sống của từng phiên bị revoke.
        List<UUID> revokedAccessJtis = refreshTokenService.revokeAllForAccount(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);

        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
        assertThat(revokedAccessJtis).containsExactlyInAnyOrder(accessJti1, accessJti2);
    }
}
