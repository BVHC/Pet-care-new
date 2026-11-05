package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
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
import com.petcare.module.care.job.NotificationCleanupJob;
import com.petcare.module.care.job.NotificationCleanupProperties;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.module.care.service.NotificationCleanupService;
import com.petcare.support.MutableClock;

/**
 * Job dọn {@code notifications} trên Postgres 17 thật (docs/adr/0112, trả D023): đã đọc xóa sau 30 ngày, chưa đọc xóa
 * sau 90 ngày (tính từ {@code created_at}), thông báo mới không bao giờ bị chạm, mỗi lô một transaction, không chờ
 * dòng đang bị khóa. Clock ở năm 1984 — cũ hơn mọi IT khác — nên mốc cắt chỉ chạm dòng của test này; dòng được nhận ra
 * bằng email tài khoản {@code notif-cleanup-it-…} và được dọn trước / sau mỗi test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NotificationCleanupIT.TestBeans.class})
class NotificationCleanupIT {

    private static final Instant T0 = Instant.parse("1984-06-15T03:50:23Z");
    private static final String EMAIL_PREFIX = "notif-cleanup-it-";
    private static final AtomicInteger SEQ = new AtomicInteger();

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
    private NotificationCleanupJob job;

    @Autowired
    private NotificationCleanupService cleanup;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long account;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        purge();
        account = account("ACTIVE", false);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM notifications WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE ?)",
                EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM accounts WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    // ---------------------------------------------------------------- xóa đúng, giữ đúng

    /** Thông báo mới (dưới 30 ngày) đã đọc hay chưa đều không bị chạm. */
    @Test
    void freshRowsKept() {
        long read1 = row(account, true, daysAgo(1));
        long unread1 = row(account, false, daysAgo(1));
        long read29 = row(account, true, daysAgo(29));
        long unread29 = row(account, false, daysAgo(29));
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);

        job.run();

        assertThat(List.of(read1, unread1, read29, unread29)).allMatch(this::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).as("không audit")
                .isEqualTo(auditBefore);
    }

    /** Điều kiện là {@code <}: dòng đúng bằng mốc cắt chưa đủ cũ. */
    @Test
    void readRowsDeletedBeyond30DaysAndExactCutoffKept() {
        long read31 = row(account, true, daysAgo(31));
        long read200 = row(account, true, daysAgo(200));
        long atCutoff = row(account, true, daysAgo(30));
        long justBefore = row(account, true, daysAgo(30).minusSeconds(1));

        job.run();

        assertThat(exists(read31)).as("đã đọc 31 ngày").isFalse();
        assertThat(exists(read200)).as("đã đọc 200 ngày").isFalse();
        assertThat(exists(atCutoff)).as("đúng mốc 30 ngày").isTrue();
        assertThat(exists(justBefore)).as("trước mốc 1 giây").isFalse();
    }

    /** Chưa đọc giữ tới 90 ngày (khác đã đọc 30 ngày); số chưa đọc giảm khi dòng quá 90 ngày bị xóa. */
    @Test
    void unreadRowsKeptUntil90DaysAndCountDropsAfterwards() {
        long unread31 = row(account, false, daysAgo(31));
        long unread89 = row(account, false, daysAgo(89));
        long atCutoff = row(account, false, daysAgo(90));
        long unread91 = row(account, false, daysAgo(91));
        assertThat(notifications.countUnread(account)).isEqualTo(4);

        job.run();

        assertThat(exists(unread31)).as("chưa đọc 31 ngày").isTrue();
        assertThat(exists(unread89)).as("chưa đọc 89 ngày").isTrue();
        assertThat(exists(atCutoff)).as("đúng mốc 90 ngày").isTrue();
        assertThat(exists(unread91)).as("chưa đọc 91 ngày").isFalse();
        assertThat(notifications.countUnread(account)).isEqualTo(3);
    }

    /** Dòng chưa đọc 40 ngày được giữ; sau khi người dùng đọc hết, đêm sau nó đi theo mốc 30 ngày và bị xóa. */
    @Test
    void readByMarkAllThenOlderThan30DaysIsDeletedNextRun() {
        long unread40 = row(account, false, daysAgo(40));

        job.run();
        assertThat(exists(unread40)).as("chưa đọc 40 ngày được giữ").isTrue();

        inTx(() -> notifications.markAllRead(account, T0));
        job.run();

        assertThat(exists(unread40)).as("đã đọc, quá 30 ngày").isFalse();
    }

    /** Job không đọc {@code accounts}: trạng thái tài khoản không đổi kết quả, tài khoản không bị chạm. */
    @Test
    void deletesRegardlessOfAccountStatusAndLeavesAccountsAlone() {
        long locked = account("ACTIVE", true);
        long disabled = account("DISABLED", false);
        List<Long> ids = List.of(account, locked, disabled).stream()
                .map(owner -> row(owner, true, daysAgo(60))).toList();

        job.run();

        assertThat(ids).noneMatch(this::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email LIKE ?", Long.class,
                EMAIL_PREFIX + "%")).isEqualTo(3);
    }

    @Test
    void rerunDeletesNothing() {
        IntStream.rangeClosed(1, 3).forEach(i -> row(account, true, daysAgo(60)));
        IntStream.rangeClosed(1, 2).forEach(i -> row(account, false, daysAgo(100)));

        assertThat(cleanup.deleteReadBatch(daysAgo(30), 100)).isEqualTo(3);
        assertThat(cleanup.deleteUnreadBatch(daysAgo(90), 100)).isEqualTo(2);
        assertThat(cleanup.deleteReadBatch(daysAgo(30), 100)).isZero();
        assertThat(cleanup.deleteUnreadBatch(daysAgo(90), 100)).isZero();
    }

    /** Đổi thời hạn giữa hai lượt: mốc tính lại mỗi lượt, tăng thì giữ thêm, giảm thì lượt sau xóa bù. */
    @Test
    void retentionChangeAppliesNextRun() {
        long read45 = row(account, true, daysAgo(45));
        NotificationCleanupJob longer = new NotificationCleanupJob(cleanup,
                new NotificationCleanupProperties("-", 60, 120, 100, Duration.ofSeconds(60)), clock);

        longer.run();
        assertThat(exists(read45)).as("giữ 60 ngày").isTrue();

        job.run();
        assertThat(exists(read45)).as("giữ 30 ngày").isFalse();
    }

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("NotificationCleanupJob"));
    }

    // ---------------------------------------------------------------- bấm vào dòng đã bị xóa

    /** Tiền đề của 404 ở care-v1 #7: {@code markRead} trả 0 và dòng không còn nên {@code exists} false. */
    @Test
    void markReadOfDeletedRowChangesNothingAndRowIsGone() {
        long old = row(account, false, daysAgo(100));

        job.run();

        assertThat(inTx(() -> notifications.markRead(old, account, T0))).isZero();
        assertThat(notifications.existsByIdAndAccountId(old, account)).isFalse();
    }

    // ---------------------------------------------------------------- theo lô, mỗi lô một transaction

    @Test
    void deletesInBatchesOfAtMostLimit() {
        IntStream.rangeClosed(1, 5).forEach(i -> row(account, true, daysAgo(60)));

        assertThat(List.of(batch(2), batch(2), batch(2), batch(2))).containsExactly(2, 2, 1, 0);
    }

    /** Lô 2 của lượt đã đọc lỗi: lô 1 đã commit riêng nên vẫn mất, các dòng còn lại giữ nguyên, job không ném. */
    @Test
    void failedBatchKeepsEarlierBatchesDeleted() {
        List<Long> ids = IntStream.rangeClosed(1, 5).mapToObj(i -> row(account, true, daysAgo(60))).sorted().toList();
        NotificationCleanupService failingOnSecondBatch = new NotificationCleanupService(null) {
            private int calls;

            @Override
            public int deleteReadBatch(Instant cutoff, int limit) {
                if (++calls == 2) {
                    throw new IllegalStateException("NotificationCleanupIT: lô 2 lỗi");
                }
                return cleanup.deleteReadBatch(cutoff, limit);
            }
        };
        NotificationCleanupJob failingJob = new NotificationCleanupJob(failingOnSecondBatch,
                new NotificationCleanupProperties("-", 30, 90, 2, Duration.ofSeconds(60)), clock);

        assertThatCode(failingJob::run).doesNotThrowAnyException();

        assertThat(ids.stream().filter(this::exists).toList()).containsExactlyElementsOf(ids.subList(2, 5));
        job.run();
        assertThat(ids).noneMatch(this::exists);
    }

    /** Hai lượt chạy song song: mỗi dòng bị xóa đúng một lần (tổng số dòng đã xóa = số dòng), không lỗi. */
    @Test
    void twoConcurrentRunsDeleteEachRowOnce() throws Exception {
        int rows = 30;
        IntStream.rangeClosed(1, rows).forEach(i -> row(account, i % 2 == 0, daysAgo(120)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> runs = List.of(executor.submit(() -> drain(go)), executor.submit(() -> drain(go)));
            go.countDown();

            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(30, TimeUnit.SECONDS);
            }

            assertThat(total).isEqualTo(rows);
            assertThat(remainingRows()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private int drain(CountDownLatch go) throws InterruptedException {
        go.await(10, TimeUnit.SECONDS);
        int total = 0;
        int batch;
        do {
            batch = cleanup.deleteReadBatch(daysAgo(30), 4) + cleanup.deleteUnreadBatch(daysAgo(90), 4);
            total += batch;
        } while (batch > 0);
        return total;
    }

    // ---------------------------------------------------------------- không chờ dòng đang bị khóa

    /** Bỏ {@code SKIP LOCKED} thì câu xóa treo tới khi khóa được nhả → hết 5 giây, đỏ. */
    @Test
    void skipsRowsLockedByConcurrentTransaction() throws Exception {
        long locked = row(account, true, daysAgo(61));
        long free = row(account, true, daysAgo(60));

        runWhileHolding(() -> jdbc.queryForObject("SELECT id FROM notifications WHERE id = ? FOR UPDATE", Long.class,
                locked), () -> {
            assertThat(timedReadBatch()).isEqualTo(1);
            assertThat(exists(locked)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(batch(100)).as("lượt sau xóa bù").isEqualTo(1);
        assertThat(exists(locked)).isFalse();
    }

    /**
     * {@code markRead} đang giữ một dòng chưa đọc 100 ngày (chưa commit): lượt chưa đọc bỏ qua nó, không chờ. Khi đã
     * commit, dòng thành "đã đọc" nên lượt đã đọc của đêm sau xóa nó.
     */
    @Test
    void markReadHoldingRowDoesNotBlockCleanup() throws Exception {
        long held = row(account, false, daysAgo(100));
        long free = row(account, false, daysAgo(101));

        runWhileHolding(() -> notifications.markRead(held, account, T0), () -> {
            assertThat(timedUnreadBatch()).isEqualTo(1);
            assertThat(exists(held)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(cleanup.deleteUnreadBatch(daysAgo(90), 100)).as("đã thành 'đã đọc'").isZero();
        assertThat(cleanup.deleteReadBatch(daysAgo(30), 100)).isEqualTo(1);
        assertThat(exists(held)).isFalse();
    }

    /** {@code markAllRead} đang giữ mọi dòng chưa đọc của tài khoản: dòng của tài khoản khác vẫn được xóa, không chờ. */
    @Test
    void markAllReadHoldingRowsDoesNotBlockCleanup() throws Exception {
        long other = account("ACTIVE", false);
        long mineA = row(account, false, daysAgo(100));
        long mineB = row(account, false, daysAgo(101));
        long theirs = row(other, false, daysAgo(102));

        runWhileHolding(() -> notifications.markAllRead(account, T0), () -> {
            assertThat(timedUnreadBatch()).isEqualTo(1);
            assertThat(exists(mineA)).isTrue();
            assertThat(exists(mineB)).isTrue();
            assertThat(exists(theirs)).isFalse();
        });

        job.run();
        assertThat(exists(mineA)).isFalse();
        assertThat(exists(mineB)).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private int batch(int limit) {
        return cleanup.deleteReadBatch(daysAgo(30), limit);
    }

    private int timedReadBatch() {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> batch(100));
    }

    private int timedUnreadBatch() {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> cleanup.deleteUnreadBatch(daysAgo(90), 100));
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

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static Instant daysAgo(int days) {
        return T0.minus(Duration.ofDays(days));
    }

    private static String email() {
        return EMAIL_PREFIX + UUID.randomUUID() + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    /** Tài khoản nhân viên (có SĐT); {@code status} ACTIVE hoặc DISABLED, có thể bị khóa. */
    private long account(String status, boolean locked) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status, is_locked)
                VALUES (?, '0900000000', 'hash', 'RECEPTIONIST', ?, ?) RETURNING id
                """, Long.class, email(), status, locked);
    }

    /** Thông báo của {@code owner} tạo lúc {@code createdAt}; đã đọc thì {@code read_at = created_at + 1 phút}. */
    private long row(long owner, boolean read, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (account_id, type, title, body, read_at, created_at, updated_at)
                VALUES (?, 'IT_CLEANUP', 'Tiêu đề', 'Nội dung', ?, ?, ?) RETURNING id
                """, Long.class, owner, read ? Timestamp.from(createdAt.plusSeconds(60)) : null,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Long.class, id) == 1;
    }

    private long remainingRows() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE account_id IN (SELECT id FROM accounts WHERE email LIKE ?)
                """, Long.class, EMAIL_PREFIX + "%");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
