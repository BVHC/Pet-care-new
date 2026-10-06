package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.job.SessionCleanupJob;
import com.petcare.module.identity.job.SessionCleanupProperties;
import com.petcare.module.identity.service.SessionCleanupService;
import com.petcare.platform.config.TimeConfig;

/**
 * Job dọn phiên trên Postgres 17 thật (docs/adr/0008): xóa đúng dòng, giữ đúng dòng, mỗi lô một transaction,
 * không chờ dòng đang bị khóa, không chạm bảng khác. Các case đếm chính xác dùng {@code expires_at} trước năm 2010
 * (không test nào khác tạo), và dọn vùng đó trước mỗi test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SessionCleanupIT {

    /** Mốc cắt của các case đếm chính xác. */
    private static final Instant ERA_CUTOFF = Instant.parse("2000-02-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionCleanupJob job;

    @Autowired
    private SessionCleanupService cleanup;

    @Autowired
    private Clock clock;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long account;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sessions WHERE expires_at < '2010-01-01'");
        account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "cleanup-it-" + UUID.randomUUID() + "@petcare.test");
    }

    // ---------------------------------------------------------------- AC1, AC2, AC5: xóa đúng, giữ đúng

    @Test
    void deletesSessionsExpiredBeyondRetentionAndKeepsTheRest() {
        Instant now = Instant.now(clock);
        long expired31 = session(now.minus(Duration.ofDays(31)), null);
        long expired31Revoked = session(now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(31).plusHours(3)));
        long expired29Revoked = session(now.minus(Duration.ofDays(29)), now.minus(Duration.ofDays(29).plusHours(3)));
        long revokedYesterday = session(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1).plusHours(2)));
        long live = session(now.plus(Duration.ofHours(12)), null);
        long auditBefore = auditCount();

        job.run();

        assertThat(exists(expired31)).as("hết hạn 31 ngày, chưa hủy").isFalse();
        assertThat(exists(expired31Revoked)).as("hết hạn 31 ngày, đã hủy").isFalse();
        assertThat(exists(expired29Revoked)).as("hết hạn 29 ngày").isTrue();
        assertThat(exists(revokedYesterday)).as("hủy hôm qua").isTrue();
        assertThat(exists(live)).as("còn hạn").isTrue();
        assertThat(auditCount()).as("không ghi audit").isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Long.class, account))
                .as("tài khoản còn nguyên").isEqualTo(1L);
    }

    /** Điều kiện là {@code <}: dòng đúng bằng mốc cắt chưa đủ cũ. */
    @Test
    void rowExactlyAtCutoffIsKept() {
        long atCutoff = session(ERA_CUTOFF, null);
        long justBefore = session(ERA_CUTOFF.minusSeconds(1), null);

        assertThat(cleanup.deleteExpiredBatch(ERA_CUTOFF, 100)).isEqualTo(1);

        assertThat(exists(atCutoff)).isTrue();
        assertThat(exists(justBefore)).isFalse();
    }

    // ---------------------------------------------------------------- AC6, AC7: theo lô, mỗi lô một transaction

    @Test
    void deletesInBatchesOfAtMostLimit() {
        IntStream.rangeClosed(1, 5).forEach(day -> session(Instant.parse("2000-01-0" + day + "T00:00:00Z"), null));

        assertThat(List.of(cleanup.deleteExpiredBatch(ERA_CUTOFF, 2), cleanup.deleteExpiredBatch(ERA_CUTOFF, 2),
                cleanup.deleteExpiredBatch(ERA_CUTOFF, 2), cleanup.deleteExpiredBatch(ERA_CUTOFF, 2)))
                .containsExactly(2, 2, 1, 0);
    }

    /**
     * Lô 2 lỗi: lô 1 đã commit riêng nên vẫn mất, các dòng còn lại giữ nguyên, job không ném ra scheduler. Nếu
     * transaction bao cả lượt chạy thì lô 1 bị rollback theo và test này đỏ.
     */
    @Test
    void failedBatchKeepsEarlierBatchesDeleted() {
        List<Long> ids = IntStream.rangeClosed(1, 5)
                .mapToObj(day -> session(Instant.parse("2000-01-0" + day + "T00:00:00Z"), null)).toList();
        SessionCleanupService failingOnSecondBatch = new SessionCleanupService(null) {
            private int calls;

            @Override
            public int deleteExpiredBatch(Instant cutoff, int limit) {
                if (++calls == 2) {
                    throw new IllegalStateException("SessionCleanupIT: lô 2 lỗi");
                }
                return cleanup.deleteExpiredBatch(cutoff, limit);
            }
        };
        // now − 30 ngày = 2000-01-31: chỉ chạm các dòng của test này
        Clock fixed = Clock.fixed(Instant.parse("2000-03-01T00:00:00Z"), TimeConfig.BUSINESS_ZONE);
        SessionCleanupJob failingJob = new SessionCleanupJob(failingOnSecondBatch,
                new SessionCleanupProperties("-", 30, 2), fixed);

        assertThatCode(failingJob::run).doesNotThrowAnyException();

        assertThat(ids.stream().filter(this::exists).toList()).containsExactlyElementsOf(ids.subList(2, 5));
    }

    // ---------------------------------------------------------------- AC13: không chờ dòng đang bị khóa

    /**
     * Use case khác (revokeAll khi khóa tài khoản…) đang giữ khóa một dòng hết hạn: job bỏ qua dòng đó thay vì chờ,
     * nên không thể có chu trình deadlock. Bỏ {@code SKIP LOCKED} thì job treo tới khi khóa được nhả → hết 5 giây, đỏ.
     */
    @Test
    void skipsRowsLockedByConcurrentTransaction() throws Exception {
        long locked = session(Instant.parse("2000-01-01T00:00:00Z"), null);
        long free = session(Instant.parse("2000-01-02T00:00:00Z"), null);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM sessions WHERE id = ? FOR UPDATE", Long.class, locked);
                        rowLocked.countDown();
                        await(release);
                    }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            int deleted = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> cleanup.deleteExpiredBatch(ERA_CUTOFF, 100));

            assertThat(deleted).isEqualTo(1);
            assertThat(exists(locked)).isTrue();
            assertThat(exists(free)).isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(cleanup.deleteExpiredBatch(ERA_CUTOFF, 100)).as("lượt sau xóa bù").isEqualTo(1);
            assertThat(exists(locked)).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- AC8: profile test không lên lịch job

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("SessionCleanupJob"));
    }

    // ---------------------------------------------------------------- helpers

    private long session(Instant expiresAt, Instant revokedAt) {
        return jdbc.queryForObject("""
                INSERT INTO sessions (account_id, token_hash, expires_at, revoked_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, account, UUID.randomUUID().toString(), Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt));
    }

    private boolean exists(long sessionId) {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id = ?", Long.class, sessionId) == 1;
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
