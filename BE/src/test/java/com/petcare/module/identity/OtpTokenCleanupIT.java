package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.job.OtpTokenCleanupJob;
import com.petcare.module.identity.job.OtpTokenCleanupProperties;
import com.petcare.module.identity.repository.OtpTokenRepository;
import com.petcare.module.identity.service.OtpService;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpTokenCleanupService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.support.MutableClock;

/**
 * Job dọn {@code otp_tokens} trên Postgres 17 thật (docs/adr/0111, trả D012): xóa mã hết hạn quá 1 ngày ở mọi mục đích
 * và trạng thái, không chạm mã còn hiệu lực hay dòng trong cửa sổ quota BR-TK-07, không chờ dòng đang bị khóa, mỗi lô
 * một transaction. Clock ở năm 1985 — cũ hơn mọi IT khác — nên mốc cắt chỉ chạm dòng của test này; dòng của test được
 * nhận ra bằng email {@code otp-cleanup-it-…} và được dọn trước / sau mỗi test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OtpTokenCleanupIT.TestBeans.class})
class OtpTokenCleanupIT {

    private static final Instant T0 = Instant.parse("1985-06-15T03:20:17Z");
    private static final String EMAIL_PREFIX = "otp-cleanup-it-";
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
    private OtpTokenCleanupJob job;

    @Autowired
    private OtpTokenCleanupService cleanup;

    @Autowired
    private OtpTokenCleanupProperties properties;

    @Autowired
    private OtpTokenRepository otps;

    @Autowired
    private OtpService otpService;

    @Autowired
    private SystemConfigService configs;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM otp_tokens WHERE target_email LIKE ?", EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM customers WHERE email LIKE ?", EMAIL_PREFIX + "%");
        jdbc.update("DELETE FROM accounts WHERE email LIKE ?", EMAIL_PREFIX + "%");
    }

    // ---------------------------------------------------------------- xóa đúng, giữ đúng

    /** Điều kiện chỉ theo {@code expires_at}: 4 mục đích × (đã dùng / đã bị thay / chưa dùng) đều bị xóa. */
    @Test
    void deletesAllPurposesAndStatesBeyondRetention() {
        long customer = customer();
        List<Long> ids = new java.util.ArrayList<>();
        for (OtpPurpose purpose : OtpPurpose.values()) {
            Long customerId = purpose == OtpPurpose.LINK_PROFILE ? customer : null;
            ids.add(token(purpose, email(), null, customerId, expiredAgo(Duration.ofDays(2)), true, false));
            ids.add(token(purpose, email(), null, customerId, expiredAgo(Duration.ofDays(2)), false, true));
            ids.add(token(purpose, email(), null, customerId, expiredAgo(Duration.ofDays(2)), false, false));
        }
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);

        job.run();

        assertThat(ids).hasSize(OtpPurpose.values().length * 3).noneMatch(this::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).as("không audit")
                .isEqualTo(auditBefore);
    }

    /** Mã còn hạn và mã mới hết hạn trong thời hạn giữ không bị chạm; điều kiện là {@code <} chặt. */
    @Test
    void keepsLiveAndRecentlyExpiredAndCutsStrictlyBeforeCutoff() {
        long live = token(OtpPurpose.REGISTER, email(), null, null, T0.plus(Duration.ofMinutes(5)), false, false);
        long expired23h = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofHours(23)), true,
                false);
        Instant cutoff = T0.minus(Duration.ofDays(1));
        long atCutoff = token(OtpPurpose.REGISTER, email(), null, null, cutoff, true, false);
        long justBefore = token(OtpPurpose.REGISTER, email(), null, null, cutoff.minusSeconds(1), true, false);

        job.run();

        assertThat(exists(live)).as("mã còn hiệu lực").isTrue();
        assertThat(exists(expired23h)).as("hết hạn 23 giờ").isTrue();
        assertThat(exists(atCutoff)).as("đúng bằng mốc cắt").isTrue();
        assertThat(exists(justBefore)).as("trước mốc cắt 1 giây").isFalse();
    }

    /** Job không đọc {@code accounts}: trạng thái tài khoản không đổi kết quả, và tài khoản không bị chạm. */
    @Test
    void deletesRegardlessOfAccountStatusAndLeavesAccountsAlone() {
        long active = account("ACTIVE", false, false);
        long locked = account("ACTIVE", true, false);
        long disabled = account("DISABLED", false, false);
        long pending = account("PENDING", false, true);
        List<Long> ids = List.of(active, locked, disabled, pending).stream()
                .map(account -> token(OtpPurpose.RESET_PASSWORD, email(), account, null,
                        expiredAgo(Duration.ofDays(3)), false, false))
                .toList();

        job.run();

        assertThat(ids).noneMatch(this::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email LIKE ?", Long.class,
                EMAIL_PREFIX + "%")).isEqualTo(4);
    }

    @Test
    void rerunDeletesNothing() {
        IntStream.rangeClosed(1, 3).forEach(i -> token(OtpPurpose.REGISTER, email(), null, null,
                expiredAgo(Duration.ofDays(2)), false, false));

        assertThat(batch(100)).isEqualTo(3);
        assertThat(batch(100)).isZero();
    }

    @Test
    void retentionChangeAppliesNextRun() {
        long twoDaysOld = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofDays(2)), false, false);
        OtpTokenCleanupJob longRetention = new OtpTokenCleanupJob(cleanup,
                new OtpTokenCleanupProperties("-", 3, 100, Duration.ofSeconds(60)), clock);

        longRetention.run();
        assertThat(exists(twoDaysOld)).as("giữ 3 ngày").isTrue();

        job.run();
        assertThat(exists(twoDaysOld)).as("giữ 1 ngày").isFalse();
    }

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("OtpTokenCleanupJob"));
    }

    // ---------------------------------------------------------------- quota BR-TK-07 và xác thực không đổi

    @Test
    void liveCodeKeptAndStillVerifies() {
        long account = account("ACTIVE", false, false);
        String email = email();
        IssuedOtp issued = inTx(() -> otpService.issueOtp(account, OtpPurpose.RESET_PASSWORD, email));

        job.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE target_email = ?", Long.class, email))
                .isEqualTo(1);
        assertThatCode(() -> inTx(() -> {
            otpService.consumeOtp(account, OtpPurpose.RESET_PASSWORD, email, issued.code());
            return null;
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM otp_tokens WHERE target_email = ?",
                Boolean.class, email)).isTrue();
    }

    /**
     * Cửa sổ quota ở mức tối đa 1440 phút (V2): 5 mã tạo 1439 phút trước còn nằm trong cửa sổ nên job không được xóa
     * (khách vẫn bị chặn gửi thêm), còn mã tạo từ hơn 1 ngày trước (ngoài cửa sổ) thì bị xóa.
     */
    @Test
    void quotaStillBlocksAfterCleanupAtMaxWindow() {
        long account = account("ACTIVE", false, false);
        String email = email();
        withSendWindowMinutes("1440", () -> {
            // 5 mã tạo 1439 phút trước, hết hạn sau 5 phút: expires_at = T0 − 1434 phút > mốc cắt (T0 − 1440 phút)
            List<Long> inWindow = IntStream.rangeClosed(1, 5).mapToObj(i -> token(OtpPurpose.RESET_PASSWORD, email,
                    account, null, T0.minus(Duration.ofMinutes(1439)).minusSeconds(i),
                    T0.minus(Duration.ofMinutes(1434)).minusSeconds(i), false, false)).toList();
            // tạo 1450 phút trước, hết hạn 1445 phút trước: quá mốc cắt và ngoài cửa sổ → bị xóa
            long outsideWindow = token(OtpPurpose.RESET_PASSWORD, email, account, null,
                    T0.minus(Duration.ofMinutes(1450)), T0.minus(Duration.ofMinutes(1445)), false, false);

            job.run();

            assertThat(inWindow).allMatch(this::exists);
            assertThat(exists(outsideWindow)).isFalse();
            assertThatThrownBy(() -> inTx(() -> otpService.issueOtp(account, OtpPurpose.RESET_PASSWORD, email)))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TK-07");
        });
    }

    /** Bất biến của ADR-0111: cửa sổ quota tối đa không dài hơn thời hạn giữ, nếu không job sẽ "mở" lại quota. */
    @Test
    void sendWindowMaxFitsRetention() {
        long maxWindowMinutes = Long.parseLong(jdbc.queryForObject(
                "SELECT max_value FROM system_configs WHERE key = 'otp.send_window_minutes'", String.class));

        assertThat(maxWindowMinutes).isLessThanOrEqualTo(properties.retentionDays() * 24L * 60L);
    }

    @Test
    void resendAfterLatestRowDeleted() {
        long account = account("ACTIVE", false, false);
        String email = email();
        long old = token(OtpPurpose.RESET_PASSWORD, email, account, null, expiredAgo(Duration.ofDays(3)), false,
                false);

        job.run();

        assertThat(exists(old)).isFalse();
        assertThatCode(() -> inTx(() -> otpService.issueOtp(account, OtpPurpose.RESET_PASSWORD, email)))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- theo lô, mỗi lô một transaction

    @Test
    void deletesInBatchesOfAtMostLimit() {
        IntStream.rangeClosed(1, 5).forEach(i -> token(OtpPurpose.REGISTER, email(), null, null,
                expiredAgo(Duration.ofDays(2)), false, false));

        assertThat(List.of(batch(2), batch(2), batch(2), batch(2))).containsExactly(2, 2, 1, 0);
    }

    /** Lô 2 lỗi: lô 1 đã commit riêng nên vẫn mất, các dòng còn lại giữ nguyên, job không ném ra scheduler. */
    @Test
    void failedBatchKeepsEarlierBatchesDeleted() {
        List<Long> ids = IntStream.rangeClosed(1, 5).mapToObj(i -> token(OtpPurpose.REGISTER, email(), null, null,
                expiredAgo(Duration.ofDays(2)), false, false)).sorted().toList();
        OtpTokenCleanupService failingOnSecondBatch = new OtpTokenCleanupService(null) {
            private int calls;

            @Override
            public int deleteExpiredBatch(Instant cutoff, int limit) {
                if (++calls == 2) {
                    throw new IllegalStateException("OtpTokenCleanupIT: lô 2 lỗi");
                }
                return cleanup.deleteExpiredBatch(cutoff, limit);
            }
        };
        OtpTokenCleanupJob failingJob = new OtpTokenCleanupJob(failingOnSecondBatch,
                new OtpTokenCleanupProperties("-", 1, 2, Duration.ofSeconds(60)), clock);

        assertThatCode(failingJob::run).doesNotThrowAnyException();

        assertThat(ids.stream().filter(this::exists).toList()).containsExactlyElementsOf(ids.subList(2, 5));
        job.run();
        assertThat(ids).noneMatch(this::exists);
    }

    /** Hai lượt chạy song song: mỗi dòng bị xóa đúng một lần (tổng số dòng đã xóa = số dòng), không lỗi. */
    @Test
    void twoConcurrentRunsDeleteEachRowOnce() throws Exception {
        int rows = 30;
        IntStream.rangeClosed(1, rows).forEach(i -> token(OtpPurpose.REGISTER, email(), null, null,
                expiredAgo(Duration.ofDays(2)), false, false));
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
            batch = batch(4);
            total += batch;
        } while (batch > 0);
        return total;
    }

    // ---------------------------------------------------------------- không chờ dòng đang bị khóa

    /** Bỏ {@code SKIP LOCKED} thì câu xóa treo tới khi khóa được nhả → hết 5 giây, đỏ. */
    @Test
    void skipsRowsLockedByConcurrentTransaction() throws Exception {
        long locked = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofDays(3)), false, false);
        long free = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofDays(2)), false, false);

        runWhileHolding(() -> jdbc.queryForObject("SELECT id FROM otp_tokens WHERE id = ? FOR UPDATE", Long.class,
                locked), () -> {
            assertThat(timedBatch()).isEqualTo(1);
            assertThat(exists(locked)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(batch(100)).as("lượt sau xóa bù").isEqualTo(1);
        assertThat(exists(locked)).isFalse();
    }

    /** ST02 đang xóa mã của một tài khoản (giữ khóa dòng đến khi commit): job không chờ, xóa phần còn lại. */
    @Test
    void st02HoldingAccountRowsDoesNotBlockCleanup() throws Exception {
        long account = account("ACTIVE", false, false);
        long held = token(OtpPurpose.REGISTER, email(), account, null, expiredAgo(Duration.ofDays(3)), false, false);
        long free = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofDays(2)), false, false);

        runWhileHolding(() -> otps.deleteByAccountId(account), () -> {
            assertThat(timedBatch()).isEqualTo(1);
            assertThat(exists(free)).isFalse();
        });

        assertThat(exists(held)).as("ST02 đã commit việc xóa của nó").isFalse();
        assertThat(batch(100)).isZero();
    }

    /** {@code invalidateActive} (quét cả mã cũ chưa dùng) đang giữ dòng: job không chờ; dòng được xóa ở lượt sau. */
    @Test
    void invalidateActiveHoldingOldRowDoesNotBlockCleanup() throws Exception {
        String email = email();
        long held = token(OtpPurpose.REGISTER, email, null, null, expiredAgo(Duration.ofDays(3)), false, false);
        long free = token(OtpPurpose.REGISTER, email(), null, null, expiredAgo(Duration.ofDays(2)), false, false);

        runWhileHolding(() -> otps.invalidateActive(email, OtpPurpose.REGISTER, T0), () -> {
            assertThat(timedBatch()).isEqualTo(1);
            assertThat(exists(held)).isTrue();
            assertThat(exists(free)).isFalse();
        });

        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NOT NULL FROM otp_tokens WHERE id = ?",
                Boolean.class, held)).as("UPDATE đã commit").isTrue();
        assertThat(batch(100)).isEqualTo(1);
        assertThat(exists(held)).isFalse();
    }

    /** Job xóa trước: {@code invalidateActive} sau đó thấy 0 dòng, không lỗi (READ COMMITTED đọc lại dòng đã mất). */
    @Test
    void invalidateActiveAfterCleanupTouchesNothing() {
        String email = email();
        long old = token(OtpPurpose.REGISTER, email, null, null, expiredAgo(Duration.ofDays(3)), false, false);

        job.run();

        assertThat(exists(old)).isFalse();
        assertThat(inTx(() -> otps.invalidateActive(email, OtpPurpose.REGISTER, T0))).isZero();
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

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    /** Đặt [CFG] {@code otp.send_window_minutes}, chạy {@code body}, rồi trả về 60 (giá trị V2) và nạp lại cấu hình. */
    private void withSendWindowMinutes(String value, Runnable body) {
        try {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'otp.send_window_minutes'", value);
            configs.reload();
            body.run();
        } finally {
            jdbc.update("UPDATE system_configs SET value = '60' WHERE key = 'otp.send_window_minutes'");
            configs.reload();
        }
    }

    private static Instant expiredAgo(Duration ago) {
        return T0.minus(ago);
    }

    private static String email() {
        return EMAIL_PREFIX + UUID.randomUUID() + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    /** Dòng OTP có {@code created_at = expires_at − 5 phút}. */
    private long token(OtpPurpose purpose, String email, Long accountId, Long customerId, Instant expiresAt,
            boolean consumed, boolean invalidated) {
        return token(purpose, email, accountId, customerId, expiresAt.minus(Duration.ofMinutes(5)), expiresAt, consumed,
                invalidated);
    }

    private long token(OtpPurpose purpose, String email, Long accountId, Long customerId, Instant createdAt,
            Instant expiresAt, boolean consumed, boolean invalidated) {
        return jdbc.queryForObject("""
                INSERT INTO otp_tokens (account_id, purpose, target_email, customer_id, code_hash, expires_at,
                                        consumed_at, invalidated_at, created_at)
                VALUES (?, ?, ?, ?, 'hash', ?, ?, ?, ?) RETURNING id
                """, Long.class, accountId, purpose.name(), email, customerId, Timestamp.from(expiresAt),
                consumed ? Timestamp.from(createdAt.plusSeconds(60)) : null,
                invalidated ? Timestamp.from(createdAt.plusSeconds(60)) : null, Timestamp.from(createdAt));
    }

    private long customer() {
        return jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel)
                VALUES ('Khách quầy otp-cleanup', '0912345678', ?, 'COUNTER') RETURNING id
                """, Long.class, email());
    }

    /** Tài khoản nhân viên (có SĐT) hoặc, với {@code PENDING}, tài khoản khách; hạn PENDING xa để ST02 không chạm. */
    private long account(String status, boolean locked, boolean pending) {
        String role = pending ? "CUSTOMER" : "RECEPTIONIST";
        String phone = pending ? null : "0900000000";
        Timestamp pendingExpiresAt = pending ? Timestamp.from(Instant.parse("2100-01-01T00:00:00Z")) : null;
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status, is_locked, pending_expires_at)
                VALUES (?, ?, 'hash', ?, ?, ?, ?) RETURNING id
                """, Long.class, email(), phone, role, status, locked, pendingExpiresAt);
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE id = ?", Long.class, id) == 1;
    }

    private long remainingRows() {
        return jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE target_email LIKE ?", Long.class,
                EMAIL_PREFIX + "%");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
