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
        Instant expiresAt = Instant.now().plusSeconds(3600);

        RefreshTokenEntity saved = refreshTokenService.issue(accountId, "raw-token-abc", jti, expiresAt, "junit-agent", "127.0.0.1");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTokenHash()).isNotEqualTo("raw-token-abc");
        assertThat(saved.getAccountId()).isEqualTo(accountId);
        assertThat(saved.isRevoked()).isFalse();
    }

    @Test
    void rotate_revokesOldToken_andLinksReplacedBy() {
        UUID oldJti = UUID.randomUUID();
        refreshTokenService.issue(accountId, "old-raw-token", oldJti, Instant.now().plusSeconds(3600), null, null);

        UUID newJti = UUID.randomUUID();
        RefreshTokenEntity rotated = refreshTokenService.rotate("old-raw-token", "new-raw-token", newJti, Instant.now().plusSeconds(3600));

        assertThat(rotated.isRevoked()).isFalse();

        List<RefreshTokenEntity> stillActive = refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId);
        assertThat(stillActive).hasSize(1);
        assertThat(stillActive.get(0).getJti()).isEqualTo(newJti);
    }

    @Test
    void rotate_rejectsAlreadyRevokedToken() {
        refreshTokenService.issue(accountId, "single-use-token", UUID.randomUUID(), Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.revoke("single-use-token", RefreshTokenRevokeReason.LOGOUT);

        assertThatThrownBy(() -> refreshTokenService.rotate("single-use-token", "another-token", UUID.randomUUID(), Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeAllForAccount_revokesEveryActiveToken() {
        refreshTokenService.issue(accountId, "token-1", UUID.randomUUID(), Instant.now().plusSeconds(3600), null, null);
        refreshTokenService.issue(accountId, "token-2", UUID.randomUUID(), Instant.now().plusSeconds(3600), null, null);

        refreshTokenService.revokeAllForAccount(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);

        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    }
}
