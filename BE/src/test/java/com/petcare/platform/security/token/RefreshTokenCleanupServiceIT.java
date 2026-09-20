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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho RefreshTokenCleanupService trên Postgres thật
 * (Testcontainers — docs/convention/backend/09-testing.md: "Integration test:
 * @SpringBootTest + Testcontainers"). Container tự chạy Flyway migration thật
 * (V1/V2/V3) nên tự chứa hoàn toàn, không phụ thuộc DB dựng sẵn bên ngoài.
 * Xác nhận đúng chính sách retention 2 mức của ADR-0003: bucket "default"
 * (7 ngày, hết hạn tự nhiên hoặc ROTATED) vs bucket "non-ROTATED"/
 * extended-retention (30 ngày, mọi revoke khác ROTATED).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class RefreshTokenCleanupServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private RefreshTokenCleanupService cleanupService;

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

    private UUID seedToken(Instant expiresAt, Instant revokedAt, String revokeReason) {
        RefreshTokenEntity entity = new RefreshTokenEntity(accountId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID().toString(), Instant.now().minusSeconds(3600), expiresAt, null, null);
        entity.setRevokedAt(revokedAt);
        entity.setRevokeReason(revokeReason);
        return refreshTokenRepository.save(entity).getId();
    }

    @Test
    void cleanupExpiredOrRotatedBatch_deletesNaturallyExpiredOlderThan7Days_butKeepsNewer() {
        UUID oldExpired = seedToken(Instant.now().minus(8, ChronoUnit.DAYS), null, null);
        UUID recentlyExpired = seedToken(Instant.now().minus(5, ChronoUnit.DAYS), null, null);

        cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(oldExpired)).isFalse();
        assertThat(refreshTokenRepository.existsById(recentlyExpired)).isTrue();
    }

    @Test
    void cleanupExpiredOrRotatedBatch_deletesRotatedOlderThan7Days() {
        UUID id = seedToken(Instant.now().plusSeconds(3600),
                Instant.now().minus(10, ChronoUnit.DAYS), RefreshTokenRevokeReason.ROTATED);

        cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isFalse();
    }

    @Test
    void cleanup_doesNotDeleteLogoutRevokedOnly10DaysAgo() {
        UUID id = seedToken(Instant.now().plusSeconds(3600),
                Instant.now().minus(10, ChronoUnit.DAYS), RefreshTokenRevokeReason.LOGOUT);

        cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 500);
        cleanupService.cleanupExtendedRetentionBatch(Instant.now().minus(30, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isTrue();
    }

    @Test
    void cleanupExtendedRetentionBatch_deletesLogoutOlderThan30Days() {
        UUID id = seedToken(Instant.now().plusSeconds(3600),
                Instant.now().minus(35, ChronoUnit.DAYS), RefreshTokenRevokeReason.LOGOUT);

        cleanupService.cleanupExtendedRetentionBatch(Instant.now().minus(30, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isFalse();
    }

    @Test
    void cleanupExtendedRetentionBatch_deletesUnknownReasonOlderThan30Days() {
        UUID id = seedToken(Instant.now().plusSeconds(3600),
                Instant.now().minus(35, ChronoUnit.DAYS), "SOME_FUTURE_REASON");

        cleanupService.cleanupExtendedRetentionBatch(Instant.now().minus(30, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isFalse();
    }

    @Test
    void cleanupExtendedRetentionBatch_deletesNullReasonRevokedOlderThan30Days() {
        UUID id = seedToken(Instant.now().plusSeconds(3600),
                Instant.now().minus(35, ChronoUnit.DAYS), null);

        cleanupService.cleanupExtendedRetentionBatch(Instant.now().minus(30, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isFalse();
    }

    @Test
    void cleanup_doesNotDeleteWhenExpiredLongAgoButRevokedRecently() {
        // expires_at 60 ngày trước nhưng revoked_at chỉ 1 ngày trước: tính hợp lệ
        // xoá phải bám theo revoked_at một khi đã có giá trị, không fallback về
        // expires_at cũ — nếu code gộp nhầm 2 điều kiện bằng AND thay vì tách 2
        // nhánh OR độc lập, row này sẽ bị xoá nhầm.
        UUID id = seedToken(Instant.now().minus(60, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS), RefreshTokenRevokeReason.LOGOUT);

        cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 500);
        cleanupService.cleanupExtendedRetentionBatch(Instant.now().minus(30, ChronoUnit.DAYS), 500);

        assertThat(refreshTokenRepository.existsById(id)).isTrue();
    }

    @Test
    void cleanupExpiredOrRotatedBatch_respectsBatchSizeLimit() {
        List<UUID> ids = List.of(
                seedToken(Instant.now().minus(8, ChronoUnit.DAYS), null, null),
                seedToken(Instant.now().minus(9, ChronoUnit.DAYS), null, null),
                seedToken(Instant.now().minus(10, ChronoUnit.DAYS), null, null));

        int deletedFirstBatch = cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 2);
        long remainingAfterFirstBatch = ids.stream().filter(refreshTokenRepository::existsById).count();

        assertThat(deletedFirstBatch).isEqualTo(2);
        assertThat(remainingAfterFirstBatch).isEqualTo(1);

        int deletedSecondBatch = cleanupService.cleanupExpiredOrRotatedBatch(Instant.now().minus(7, ChronoUnit.DAYS), 2);
        long remainingAfterSecondBatch = ids.stream().filter(refreshTokenRepository::existsById).count();

        assertThat(deletedSecondBatch).isEqualTo(1);
        assertThat(remainingAfterSecondBatch).isEqualTo(0);
    }

    @Test
    void findUnrecognizedRevokeReasons_returnsReasonsOutsideKnownList() {
        seedToken(Instant.now().plusSeconds(3600), Instant.now().minus(1, ChronoUnit.DAYS), "WEIRD_REASON");
        seedToken(Instant.now().plusSeconds(3600), Instant.now().minus(1, ChronoUnit.DAYS), RefreshTokenRevokeReason.LOGOUT);

        List<String> unrecognized = cleanupService.findUnrecognizedRevokeReasons(
                List.of(RefreshTokenRevokeReason.LOGOUT, RefreshTokenRevokeReason.LOCK_ACCOUNT,
                        RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT));

        assertThat(unrecognized).contains("WEIRD_REASON");
        assertThat(unrecognized).doesNotContain(RefreshTokenRevokeReason.LOGOUT);
    }
}
