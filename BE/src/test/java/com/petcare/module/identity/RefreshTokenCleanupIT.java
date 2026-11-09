package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.dto.RefreshTokenResponse;
import com.petcare.module.identity.exception.InvalidRefreshTokenException;
import com.petcare.module.identity.job.RefreshTokenCleanupJob;
import com.petcare.module.identity.job.RefreshTokenCleanupProperties;
import com.petcare.module.identity.repository.RefreshTokenRepository;
import com.petcare.module.identity.service.RefreshTokenCleanupService;
import com.petcare.module.identity.service.RefreshTokenService;
import com.petcare.module.identity.service.SessionCleanupService;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.support.MutableClock;

/**
 * Job dọn {@code refresh_tokens} trên Postgres 17 thật (docs/adr/0114, trả D054): xóa token đã dùng và chưa dùng hết hạn
 * quá 1 ngày, không chạm token của phiên còn hạn, không chạm {@code sessions}, không chờ dòng đang bị khóa, mỗi lô một
 * transaction, đồng thời an toàn với {@code refresh} và với {@code SessionCleanupService}. Clock ở năm 1983 — cũ hơn mọi
 * IT khác — nên mốc cắt chỉ chạm dòng của test này; dòng của test được nhận ra bằng email {@code refresh-cleanup-it-…}
 * và được dọn trước / sau mỗi test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RefreshTokenCleanupIT.TestBeans.class})
class RefreshTokenCleanupIT {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenCleanupIT.class);
    private static final Instant T0 = Instant.parse("1983-06-15T03:10:11Z");
    private static final String EMAIL_PREFIX = "refresh-cleanup-it-";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String ANY_LOCK_WAITING = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND datname = current_database()
            """;

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
    private MutableClock clock;

    @Autowired
    private RefreshTokenCleanupJob job;

    @Autowired
    private RefreshTokenCleanupService cleanup;

    @Autowired
    private RefreshTokenCleanupProperties properties;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshService;

    @Autowired
    private SessionService sessions;

    @Autowired
    private SessionCleanupService sessionCleanup;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long account;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        purge();
        account = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email());
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        String owned = "SELECT s.id FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE a.email LIKE ?";
        jdbc.update("DELETE FROM refresh_tokens WHERE session_id IN (" + owned + ")", EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM sessions WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE ?)",
                EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM accounts WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    // ---------------------------------------------------------------- xóa đúng, giữ đúng

    /** Điều kiện chỉ theo {@code expires_at}: token đã dùng và chưa dùng của phiên đã hết hạn quá 1 ngày đều bị xóa. */
    @Test
    void deletesUsedAndUnusedTokensExpiredBeyondRetention() {
        long used = tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true);
        long unused = tokenOnNewSession(expiredAgo(Duration.ofDays(2)), false);
        long revokedSessionUnused = tokenOnRevokedSession(expiredAgo(Duration.ofDays(3)));
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);

        job.run();

        assertThat(List.of(used, unused, revokedSessionUnused)).noneMatch(this::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).as("không audit")
                .isEqualTo(auditBefore);
    }

    /** Token của phiên còn hạn (đã dùng lẫn chưa dùng) và token mới hết hạn trong thời hạn giữ không bị chạm; {@code <} chặt. */
    @Test
    void keepsLiveAndRecentlyExpiredAndCutsStrictlyBeforeCutoff() {
        long sessionId = session(T0.plus(Duration.ofHours(6)), null);
        long liveUsed = token(sessionId, T0.plus(Duration.ofHours(6)), true);
        long liveUnused = token(sessionId, T0.plus(Duration.ofHours(6)), false);
        long expired23h = tokenOnNewSession(expiredAgo(Duration.ofHours(23)), true);
        Instant cutoff = T0.minus(Duration.ofDays(1));
        long atCutoff = tokenOnNewSession(cutoff, true);
        long justBefore = tokenOnNewSession(cutoff.minusSeconds(1), true);

        job.run();

        assertThat(exists(liveUsed)).as("đã dùng, phiên còn hạn").isTrue();
        assertThat(exists(liveUnused)).as("chưa dùng, phiên còn hạn").isTrue();
        assertThat(exists(expired23h)).as("hết hạn 23 giờ").isTrue();
        assertThat(exists(atCutoff)).as("đúng bằng mốc cắt").isTrue();
        assertThat(exists(justBefore)).as("trước mốc cắt 1 giây").isFalse();
    }

    /** Job chỉ chạm {@code refresh_tokens}: phiên và tài khoản còn nguyên. */
    @Test
    void leavesSessionsAndAccountsAlone() {
        long sessionId = session(expiredAgo(Duration.ofDays(5)), null);
        token(sessionId, expiredAgo(Duration.ofDays(5)), true);
        token(sessionId, expiredAgo(Duration.ofDays(5)), false);

        job.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                sessionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id = ?", Long.class, sessionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Long.class, account))
                .isEqualTo(1);
    }

    /** Job dọn phiên chạy sau vẫn xóa được phiên (token của nó đã bị job này xóa trước, không còn gì để xóa). */
    @Test
    void sessionCleanupStillDeletesSessionsWhoseTokensAreAlreadyGone() {
        long sessionId = session(expiredAgo(Duration.ofDays(40)), null);
        token(sessionId, expiredAgo(Duration.ofDays(40)), true);
        token(sessionId, expiredAgo(Duration.ofDays(40)), false);

        job.run();
        int deleted = sessionCleanup.deleteExpiredBatch(T0.minus(Duration.ofDays(30)), 100);

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE id = ?", Long.class, sessionId)).isZero();
    }

    @Test
    void rerunDeletesNothing() {
        IntStream.rangeClosed(1, 3).forEach(i -> tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true));

        assertThat(batch(100)).isEqualTo(3);
        assertThat(batch(100)).isZero();
    }

    @Test
    void retentionChangeAppliesNextRun() {
        long twoDaysOld = tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true);
        RefreshTokenCleanupJob longRetention = new RefreshTokenCleanupJob(cleanup,
                new RefreshTokenCleanupProperties("-", 3, 100, Duration.ofSeconds(60)), clock);

        longRetention.run();
        assertThat(exists(twoDaysOld)).as("giữ 3 ngày").isTrue();

        job.run();
        assertThat(exists(twoDaysOld)).as("giữ 1 ngày").isFalse();
    }

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("RefreshTokenCleanupJob"));
    }

    // ---------------------------------------------------------------- nền tảng của lập luận: expires_at = hạn phiên

    /**
     * Cả lúc mở phiên lẫn mỗi lần refresh, {@code refresh_tokens.expires_at} bằng {@code sessions.expires_at}. Đây là
     * điều kiện để dòng bị job xóa luôn thuộc phiên đã hết hạn (nhánh phát hiện dùng lại cần phiên còn hạn). Ai cho phép
     * gia hạn phiên hoặc cấp token sống lâu hơn phiên sẽ làm test này đỏ.
     */
    @Test
    void everyTokenExpiresExactlyWhenItsSessionDoes() {
        OpenedSession opened = open();
        clock.advance(Duration.ofMinutes(3));
        RefreshTokenResponse first = refreshService.refresh(opened.refreshToken());
        clock.advance(Duration.ofMinutes(3));
        refreshService.refresh(first.refreshToken());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                opened.sessionId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens t JOIN sessions s ON s.id = t.session_id
                WHERE t.session_id = ? AND t.expires_at <> s.expires_at
                """, Long.class, opened.sessionId())).isZero();
    }

    /**
     * Token đã bị job xóa và token còn nguyên cho cùng một kết quả: 401 và không hủy phiên (phiên đã hết hạn nên nhánh
     * phát hiện dùng lại không chạy). Job không làm mất khả năng phát hiện lộ token.
     */
    @Test
    void deletedTokenIsRejectedExactlyLikeAnExpiredOneAndRevokesNothing() {
        OpenedSession opened = open();
        clock.advance(Duration.ofMinutes(3));
        RefreshTokenResponse rotated = refreshService.refresh(opened.refreshToken());
        clock.set(opened.expiresAt().plus(Duration.ofDays(2)));

        assertThatThrownBy(() -> refreshService.refresh(opened.refreshToken()))
                .as("trước khi dọn: token đã dùng của phiên hết hạn").isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(sessionRevokedAt(opened.sessionId())).isNull();

        job.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                opened.sessionId())).as("cả hai token đã bị xóa").isZero();
        assertThatThrownBy(() -> refreshService.refresh(opened.refreshToken()))
                .as("sau khi dọn: token đã dùng").isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> refreshService.refresh(rotated.refreshToken()))
                .as("sau khi dọn: token chưa dùng").isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(sessionRevokedAt(opened.sessionId())).as("không hủy phiên").isNull();
    }

    /** Phiên còn hạn: phát hiện dùng lại vẫn chạy, job không xóa token đã dùng của nó. */
    @Test
    void reuseOfSpentTokenStillRevokesLiveSessionAfterCleanupRuns() {
        OpenedSession opened = open();
        clock.advance(Duration.ofMinutes(3));
        refreshService.refresh(opened.refreshToken());
        clock.advance(Duration.ofHours(1));

        job.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id = ?", Long.class,
                opened.sessionId())).as("token đã dùng còn nguyên").isEqualTo(2);
        assertThatThrownBy(() -> refreshService.refresh(opened.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(sessionRevokedAt(opened.sessionId())).as("dùng lại token cũ hủy phiên").isNotNull();
    }

    // ---------------------------------------------------------------- theo lô, mỗi lô một transaction

    @Test
    void deletesInBatchesOfAtMostLimit() {
        IntStream.rangeClosed(1, 5).forEach(i -> tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true));

        assertThat(List.of(batch(2), batch(2), batch(2), batch(2))).containsExactly(2, 2, 1, 0);
    }

    /** Lô 2 lỗi: lô 1 đã commit riêng nên vẫn mất, các dòng còn lại giữ nguyên, job không ném ra scheduler. */
    @Test
    void failedBatchKeepsEarlierBatchesDeleted() {
        List<Long> ids = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true)).sorted().toList();
        RefreshTokenCleanupService failingOnSecondBatch = new RefreshTokenCleanupService(null) {
            private int calls;

            @Override
            public int deleteExpiredBatch(Instant cutoff, int limit) {
                if (++calls == 2) {
                    throw new IllegalStateException("RefreshTokenCleanupIT: lô 2 lỗi");
                }
                return cleanup.deleteExpiredBatch(cutoff, limit);
            }
        };
        RefreshTokenCleanupJob failingJob = new RefreshTokenCleanupJob(failingOnSecondBatch,
                new RefreshTokenCleanupProperties("-", 1, 2, Duration.ofSeconds(60)), clock);

        assertThatCode(failingJob::run).doesNotThrowAnyException();

        assertThat(ids.stream().filter(this::exists).toList()).containsExactlyElementsOf(ids.subList(2, 5));
        job.run();
        assertThat(ids).noneMatch(this::exists);
    }

    /** Hai lượt chạy song song: mỗi dòng bị xóa đúng một lần (tổng số dòng đã xóa = số dòng), không lỗi. */
    @Test
    void twoConcurrentRunsDeleteEachRowOnce() throws Exception {
        int rows = 30;
        IntStream.rangeClosed(1, rows).forEach(i -> tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> runs = List.of(
                    executor.submit(() -> drain(go)),
                    executor.submit(() -> drain(go)));
            go.countDown();

            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(30, TimeUnit.SECONDS);
            }

            assertThat(total).isEqualTo(rows);
            assertThat(remainingTokens()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private int drain(CountDownLatch go) throws InterruptedException {
        go.await(10, TimeUnit.SECONDS);
        int total = 0;
        int batch;
        do {
            batch = batch(4);
            total += batch;
        } while (batch > 0);
        return total;
    }

    // ---------------------------------------------------------------- đồng thời với refresh (cùng một dòng)

    /** Bỏ {@code SKIP LOCKED} thì câu xóa treo tới khi khóa được nhả → hết 5 giây, đỏ. */
    @Test
    void skipsRowsLockedByConcurrentTransaction() throws Exception {
        long locked = tokenOnNewSession(expiredAgo(Duration.ofDays(3)), true);
        long free = tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true);

        runWhileHolding(() -> jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE id = ? FOR UPDATE", Long.class,
                locked), () -> {
            assertThat(timedBatch()).isEqualTo(1);
            assertThat(exists(locked)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(batch(100)).as("lượt sau xóa bù").isEqualTo(1);
        assertThat(exists(locked)).isFalse();
    }

    /**
     * Refresh đang giữ khóa dòng token hết hạn (đúng câu khóa của {@code RefreshTokenService}): job bỏ qua dòng đó,
     * không chờ; refresh xong thì lượt sau xóa bù.
     */
    @Test
    void refreshHoldingTheRowDoesNotBlockTheJob() throws Exception {
        String raw = "held-" + UUID.randomUUID();
        long held = tokenOnNewSession(sha256(raw), expiredAgo(Duration.ofDays(3)), false);
        long free = tokenOnNewSession(expiredAgo(Duration.ofDays(2)), true);

        runWhileHolding(() -> refreshTokenRepository.findByTokenHashForUpdate(sha256(raw)).orElseThrow(), () -> {
            assertThat(timedBatch()).isEqualTo(1);
            assertThat(exists(held)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(batch(100)).isEqualTo(1);
        assertThat(exists(held)).isFalse();
    }

    /**
     * Đảo chiều: lô của job đang giữ khóa (đã xóa, chưa commit) dòng token hết hạn; refresh gửi đúng token đó phải
     * chờ rồi nhận 401 khi job commit (dòng đã mất) — không treo, không lỗi 500, không hủy phiên.
     */
    @Test
    void refreshWaitsForTheJobBatchThenIsRejected() throws Exception {
        String raw = "waits-" + UUID.randomUUID();
        long sessionId = session(expiredAgo(Duration.ofDays(3)), null);
        long tokenId = token(sessionId, sha256(raw), expiredAgo(Duration.ofDays(3)), false);
        CountDownLatch batchDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> jobBatch = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        cleanup.deleteExpiredBatch(T0.minus(Duration.ofDays(1)), 100);
                        batchDone.countDown();
                        await(release);
                    }));
            assertThat(batchDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> refresh = pool.submit(() -> {
                try {
                    refreshService.refresh(raw);
                    return null;
                } catch (Throwable thrown) {
                    return thrown;
                }
            });
            awaitLockWaiting();
            assertThat(refresh.isDone()).as("refresh đang chờ khóa của lô job").isFalse();
            release.countDown();

            jobBatch.get(10, TimeUnit.SECONDS);
            assertThat(refresh.get(10, TimeUnit.SECONDS)).isInstanceOf(InvalidRefreshTokenException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(exists(tokenId)).isFalse();
        assertThat(sessionRevokedAt(sessionId)).as("không hủy phiên").isNull();
    }

    // ---------------------------------------------------------------- đồng thời với job dọn phiên (cùng dòng)

    /**
     * Job dọn phiên xóa token theo phiên (không {@code SKIP LOCKED}) còn job này xóa token theo hạn: hai bên chạm cùng
     * các dòng. Cả hai phải xong, không deadlock, không lỗi FK, không còn dòng nào. Lô nhỏ (7 / 5) để chúng xen kẽ.
     */
    @Test
    void jobAndSessionCleanupRunningTogetherFinishWithoutDeadlockOrFkError() throws Exception {
        for (int i = 0; i < 60; i++) {
            long sessionId = session(expiredAgo(Duration.ofDays(40)), null);
            token(sessionId, expiredAgo(Duration.ofDays(40)), true);
            token(sessionId, expiredAgo(Duration.ofDays(40)), true);
            token(sessionId, expiredAgo(Duration.ofDays(40)), false);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> tokens = pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                int total = 0;
                for (int n; (n = batch(7)) > 0; ) {
                    total += n;
                }
                return total;
            });
            Future<Integer> sessionRows = pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                int total = 0;
                for (int n; (n = sessionCleanup.deleteExpiredBatch(T0.minus(Duration.ofDays(30)), 5)) > 0; ) {
                    total += n;
                }
                return total;
            });
            go.countDown();

            tokens.get(60, TimeUnit.SECONDS);
            sessionRows.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // phần bị SKIP LOCKED bỏ qua trong lúc chạy song song được dọn ở lượt sau
        while (batch(100) > 0 || sessionCleanup.deleteExpiredBatch(T0.minus(Duration.ofDays(30)), 100) > 0) {
            // drain
        }
        assertThat(remainingTokens()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sessions WHERE account_id = ?
                """, Long.class, account)).isZero();
    }

    // ---------------------------------------------------------------- chi phí: chỉ mục và độ trễ

    /**
     * Dữ liệu gần thật (20 000 token trong transaction rollback + {@code ANALYZE}, khuôn {@code NoShowIT}), hai hình
     * dạng. Đêm bình thường: 50 phiên cũ ở đầu bảng (500 token hết hạn) — câu chọn lô phải dùng
     * {@code ix_refresh_tokens_expires_at} (V50) và không {@code Seq Scan}; chỉ phần dòng khớp được đọc và sắp xếp.
     * Tồn đọng lớn: mọi token đã hết hạn (20 000 dòng khớp) — phải đi index theo thứ tự {@code (expires_at, id)} và dừng
     * sau một lô, không {@code Sort}. Trước V50, Postgres chọn Seq Scan cả bảng ở cả hai hình dạng (docs/adr/0114). Kế
     * hoạch được log để ghi vào ADR.
     */
    @Test
    void lockQueryUsesTheExpiresAtIndexOnRealisticData() {
        String nightly = lockPlan(50);
        String backlog = lockPlan(2000);

        LOG.info("REFRESH_TOKEN_CLEANUP_PLAN nightly {}{}", System.lineSeparator(), nightly);
        LOG.info("REFRESH_TOKEN_CLEANUP_PLAN backlog {}{}", System.lineSeparator(), backlog);
        assertThat(nightly).as(nightly).contains("ix_refresh_tokens_expires_at").doesNotContain("Seq Scan");
        assertThat(backlog).as(backlog).contains("ix_refresh_tokens_expires_at").doesNotContain("Seq Scan")
                .doesNotContain("Sort");
    }

    private String lockPlan(int oldSessions) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            insertSyntheticTokens("EXPLAIN", oldSessions, 3, 6);
            jdbc.execute("ANALYZE refresh_tokens");
            String sql = lockSql().replaceAll(":[a-zA-Z]+", "?");
            return String.join(System.lineSeparator(), jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                    String.class, Timestamp.from(T0.minus(Duration.ofDays(1))), 1000));
        });
    }

    /** Câu xóa theo danh sách id của lô (Hibernate sinh {@code id in (?, …)}) đi theo khóa chính, không đọc cả bảng. */
    @Test
    void deleteByIdsWalksThePrimaryKeyOnRealisticData() {
        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            insertSyntheticTokens("EXPLAIN", 50, 3, 6);
            jdbc.execute("ANALYZE refresh_tokens");
            List<Long> ids = jdbc.queryForList("""
                    SELECT t.id FROM refresh_tokens t JOIN sessions s ON s.id = t.session_id
                    WHERE s.account_id = ? ORDER BY t.id LIMIT 500""", Long.class, account);
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            return String.join(System.lineSeparator(), jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) DELETE FROM "
                    + "refresh_tokens WHERE id IN (" + placeholders + ")", String.class, ids.toArray()));
        });

        LOG.info("REFRESH_TOKEN_CLEANUP_DELETE_PLAN {}{}", System.lineSeparator(), plan);
        assertThat(plan).as(plan).contains("refresh_tokens_pkey").doesNotContain("Seq Scan");
    }

    /**
     * Khói, không phải benchmark: trong lúc job xóa 20 000 token hết hạn (20 lô), người dùng thật vẫn refresh được và
     * không lần nào chờ quá 2 giây. Ghi thời gian dài nhất vào log.
     */
    @Test
    void refreshStaysResponsiveWhileTheJobDeletes() throws Exception {
        insertSyntheticTokens("LATENCY", 2000, 3, 6);
        assertThat(remainingTokens()).isEqualTo(20_000);
        OpenedSession live = open();
        String next = live.refreshToken();
        long worstMillis = 0;
        int refreshes = 0;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = pool.submit(job::run);
            do {
                long started = System.nanoTime();
                next = refreshService.refresh(next).refreshToken();
                worstMillis = Math.max(worstMillis, Duration.ofNanos(System.nanoTime() - started).toMillis());
                refreshes++;
            } while (!running.isDone());
            running.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        LOG.info("REFRESH_TOKEN_CLEANUP_LATENCY refreshes={} worstMs={}", refreshes, worstMillis);
        assertThat(worstMillis).isLessThan(2000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id <> ? AND expires_at < ?",
                Long.class, live.sessionId(), Timestamp.from(T0.minus(Duration.ofDays(1))))).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private int batch(int limit) {
        return cleanup.deleteExpiredBatch(T0.minus(Duration.ofDays(1)), limit);
    }

    private int timedBatch() {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> batch(100));
    }

    /**
     * Mở transaction ở luồng khác, chạy {@code holder} (nó khóa dòng) rồi giữ transaction mở trong lúc chạy {@code
     * during}; nhả và commit sau đó.
     */
    private void runWhileHolding(Runnable holder, Runnable during) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        holder.run();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            during.run();

            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private OpenedSession open() {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(account, "203.0.113.9", "RefreshTokenCleanupIT"));
    }

    /** 2 000 phiên × 10 token của {@code account}: {@code oldSessions} phiên đầu hết hạn từ {@code oldDays} ngày trước, phần còn lại còn hạn {@code liveHours} giờ. */
    private void insertSyntheticTokens(String tag, int oldSessions, int oldDays, int liveHours) {
        jdbc.update("""
                INSERT INTO sessions (account_id, token_hash, expires_at)
                SELECT ?, ? || '-' || ? || '-' || g,
                       CASE WHEN g <= ? THEN CAST(? AS timestamptz) ELSE CAST(? AS timestamptz) END
                FROM generate_series(1, 2000) g""", account, tag, SEQ.incrementAndGet(), oldSessions,
                Timestamp.from(expiredAgo(Duration.ofDays(oldDays))), Timestamp.from(T0.plus(Duration.ofHours(liveHours))));
        jdbc.update("""
                INSERT INTO refresh_tokens (session_id, token_hash, expires_at, used_at)
                SELECT s.id, 'TOK-' || s.id || '-' || k, s.expires_at,
                       CASE WHEN k < 10 THEN s.expires_at - INTERVAL '1 hour' END
                FROM sessions s CROSS JOIN generate_series(1, 10) k
                WHERE s.account_id = ? AND s.token_hash LIKE ? || '-%'
                ORDER BY s.id, k""", account, tag);
    }

    private static String lockSql() {
        try {
            return RefreshTokenRepository.class.getMethod("lockExpiredIdsBefore", Instant.class, int.class)
                    .getAnnotation(Query.class).value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant sessionRevokedAt(long sessionId) {
        Timestamp value = jdbc.queryForObject("SELECT revoked_at FROM sessions WHERE id = ?", Timestamp.class,
                sessionId);
        return value == null ? null : value.toInstant();
    }

    private void awaitLockWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(ANY_LOCK_WAITING, Long.class) == 0) {
            assertThat(System.nanoTime()).as("refresh phải chờ khóa dòng token").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static Instant expiredAgo(Duration ago) {
        return T0.minus(ago);
    }

    private static String email() {
        return EMAIL_PREFIX + UUID.randomUUID() + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    private long session(Instant expiresAt, Instant revokedAt) {
        return jdbc.queryForObject("""
                INSERT INTO sessions (account_id, token_hash, expires_at, revoked_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, account, UUID.randomUUID().toString(), Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt));
    }

    /** Token của một phiên cho trước; chỉ được có một token chưa dùng cho mỗi phiên ({@code ux_refresh_tokens_active_session}). */
    private long token(long sessionId, Instant expiresAt, boolean used) {
        return token(sessionId, UUID.randomUUID().toString(), expiresAt, used);
    }

    private long token(long sessionId, String tokenHash, Instant expiresAt, boolean used) {
        return jdbc.queryForObject("""
                INSERT INTO refresh_tokens (session_id, token_hash, expires_at, used_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, sessionId, tokenHash, Timestamp.from(expiresAt),
                used ? Timestamp.from(expiresAt.minus(Duration.ofHours(1))) : null);
    }

    private long tokenOnNewSession(Instant expiresAt, boolean used) {
        return token(session(expiresAt, null), expiresAt, used);
    }

    private long tokenOnNewSession(String tokenHash, Instant expiresAt, boolean used) {
        return token(session(expiresAt, null), tokenHash, expiresAt, used);
    }

    private long tokenOnRevokedSession(Instant expiresAt) {
        return token(session(expiresAt, expiresAt.minus(Duration.ofHours(2))), expiresAt, false);
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id = ?", Long.class, id) == 1;
    }

    private long remainingTokens() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens t JOIN sessions s ON s.id = t.session_id
                WHERE s.account_id = ?
                """, Long.class, account);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
