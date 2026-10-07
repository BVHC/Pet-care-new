package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.job.NotificationOutboxCleanupJob;
import com.petcare.module.care.job.NotificationOutboxCleanupProperties;
import com.petcare.module.care.service.NotificationOutboxCleanupService;
import com.petcare.support.MutableClock;

/**
 * Job dọn {@code notification_outbox} trên Postgres 17 thật (docs/adr/0016, trả nợ D007): xóa {@code SENT} quá 30
 * ngày, {@code FAILED} quá 90 ngày, cả hai kênh; không bao giờ xóa {@code PENDING}; mỗi lô một transaction; không chờ
 * dòng đang bị khóa. Bảng outbox được làm rỗng trước mỗi test như {@code NotificationOutboxIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NotificationOutboxCleanupIT.TestBeans.class})
class NotificationOutboxCleanupIT {

    private static final Instant T0 = Instant.parse("2026-10-07T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationOutboxCleanupJob job;

    @Autowired
    private NotificationOutboxCleanupService cleanup;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        jdbc.update("DELETE FROM notification_outbox");
    }

    // ---------------------------------------------------------------- xóa đúng, giữ đúng

    @Test
    void deletesProcessedRowsBeyondRetentionAndKeepsTheRest() {
        long sent31 = row("EMAIL", "SENT", daysAgo(31));
        long sent29 = row("EMAIL", "SENT", daysAgo(29));
        long inAppSent31 = row("IN_APP", "SENT", daysAgo(31));
        long failed91 = row("EMAIL", "FAILED", daysAgo(91));
        long failed89 = row("EMAIL", "FAILED", daysAgo(89));
        long failed31 = row("EMAIL", "FAILED", daysAgo(31));
        long pendingVeryOld = row("EMAIL", "PENDING", daysAgo(400));
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);

        job.run();

        assertThat(exists(sent31)).as("SENT 31 ngày").isFalse();
        assertThat(exists(inAppSent31)).as("IN_APP SENT 31 ngày").isFalse();
        assertThat(exists(failed91)).as("FAILED 91 ngày").isFalse();
        assertThat(exists(sent29)).as("SENT 29 ngày").isTrue();
        assertThat(exists(failed89)).as("FAILED 89 ngày").isTrue();
        assertThat(exists(failed31)).as("FAILED giữ lâu hơn SENT").isTrue();
        assertThat(exists(pendingVeryOld)).as("PENDING không bao giờ bị xóa").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).as("không audit")
                .isEqualTo(auditBefore);
    }

    /** Điều kiện là {@code <}: dòng đúng bằng mốc cắt chưa đủ cũ. */
    @Test
    void rowExactlyAtCutoffIsKept() {
        Instant cutoff = daysAgo(30);
        long atCutoff = row("EMAIL", "SENT", cutoff);
        long justBefore = row("EMAIL", "SENT", cutoff.minusSeconds(1));

        assertThat(cleanup.deleteProcessedBatch(OutboxStatus.SENT, cutoff, 100)).isEqualTo(1);

        assertThat(exists(atCutoff)).isTrue();
        assertThat(exists(justBefore)).isFalse();
    }

    @Test
    void statusFilterIsExact() {
        long sent = row("EMAIL", "SENT", daysAgo(200));
        long failed = row("EMAIL", "FAILED", daysAgo(200));

        assertThat(cleanup.deleteProcessedBatch(OutboxStatus.FAILED, daysAgo(90), 100)).isEqualTo(1);

        assertThat(exists(sent)).isTrue();
        assertThat(exists(failed)).isFalse();
    }

    @Test
    void pendingCannotBeCleanedEvenByMistake() {
        long pending = row("EMAIL", "PENDING", daysAgo(400));

        assertThatThrownBy(() -> cleanup.deleteProcessedBatch(OutboxStatus.PENDING, T0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(exists(pending)).isTrue();
    }

    // ---------------------------------------------------------------- theo lô, mỗi lô một transaction

    @Test
    void deletesInBatchesOfAtMostLimit() {
        IntStream.rangeClosed(1, 5).forEach(i -> row("EMAIL", "SENT", daysAgo(40 + i)));

        assertThat(List.of(batch(2), batch(2), batch(2), batch(2))).containsExactly(2, 2, 1, 0);
    }

    /** Lô 2 lỗi: lô 1 đã commit riêng nên vẫn mất, các dòng còn lại giữ nguyên, job không ném ra scheduler. */
    @Test
    void failedBatchKeepsEarlierBatchesDeleted() {
        List<Long> ids = IntStream.rangeClosed(1, 5).mapToObj(i -> row("EMAIL", "SENT", daysAgo(40 + i)))
                .sorted().toList();
        NotificationOutboxCleanupService failingOnSecondBatch = new NotificationOutboxCleanupService(null) {
            private int calls;

            @Override
            public int deleteProcessedBatch(OutboxStatus status, Instant cutoff, int limit) {
                if (++calls == 2) {
                    throw new IllegalStateException("NotificationOutboxCleanupIT: lô 2 lỗi");
                }
                return cleanup.deleteProcessedBatch(status, cutoff, limit);
            }
        };
        NotificationOutboxCleanupJob failingJob = new NotificationOutboxCleanupJob(failingOnSecondBatch,
                new NotificationOutboxCleanupProperties("-", 30, 90, 2), clock);

        assertThatCode(failingJob::run).doesNotThrowAnyException();

        assertThat(ids.stream().filter(this::exists).toList()).containsExactlyElementsOf(ids.subList(2, 5));
    }

    // ---------------------------------------------------------------- không chờ dòng đang bị khóa

    /** Bỏ {@code SKIP LOCKED} thì câu xóa treo tới khi khóa được nhả → hết 5 giây, đỏ. */
    @Test
    void skipsRowsLockedByConcurrentTransaction() throws Exception {
        long locked = row("EMAIL", "SENT", daysAgo(45));
        long free = row("EMAIL", "SENT", daysAgo(44));
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM notification_outbox WHERE id = ? FOR UPDATE", Long.class,
                                locked);
                        rowLocked.countDown();
                        await(release);
                    }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            int deleted = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> batch(100));

            assertThat(deleted).isEqualTo(1);
            assertThat(exists(locked)).isTrue();
            assertThat(exists(free)).isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(batch(100)).as("lượt sau xóa bù").isEqualTo(1);
            assertThat(exists(locked)).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("NotificationOutboxCleanupJob"));
    }

    // ---------------------------------------------------------------- helpers

    private int batch(int limit) {
        return cleanup.deleteProcessedBatch(OutboxStatus.SENT, daysAgo(30), limit);
    }

    private static Instant daysAgo(int days) {
        return T0.minus(Duration.ofDays(days));
    }

    private long row(String channel, String status, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification_outbox (channel, template_code, recipient_email, payload, status,
                                                 next_attempt_at, created_at)
                VALUES (?, 'OTP_REGISTER', 'cleanup-it@petcare.test', '{}'::jsonb, ?, ?, ?) RETURNING id
                """, Long.class, channel, status, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE id = ?", Long.class, id) == 1;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
