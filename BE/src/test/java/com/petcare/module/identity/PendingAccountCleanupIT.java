package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.job.PendingAccountCleanupJob;
import com.petcare.module.identity.job.PendingAccountCleanupProperties;
import com.petcare.module.identity.service.PendingAccountCleanupService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * ST02 — dọn tài khoản {@code PENDING} quá hạn (03 Tài khoản#3, BR-TK-08, docs/adr/0013) trên Postgres 17 thật. Tài
 * khoản được đăng ký qua HTTP ({@code /api/auth/register}) với clock ở năm 2000, nên hạn {@code pending_expires_at}
 * của test này đều trước 2010 còn mọi IT khác ở năm 2026: job chạy ở năm 2000 chỉ chạm tài khoản của test này. Vùng
 * đó được dọn trước mỗi test. {@link CustomerApi} là mock (nợ D001) ghi/xóa {@code customers} thật bằng SQL trong cùng
 * transaction, nên kiểm được rollback và số lần gọi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PendingAccountCleanupIT.TestBeans.class})
class PendingAccountCleanupIT {

    private static final Instant T0 = Instant.parse("2000-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @MockitoBean
    private CustomerApi customerApi;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PendingAccountCleanupJob job;

    @Autowired
    private PendingAccountCleanupService cleanup;

    @Autowired
    private SystemConfigService configs;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        jdbc.update("""
                DELETE FROM otp_tokens WHERE account_id IN
                    (SELECT id FROM accounts WHERE pending_expires_at < '2010-01-01')""");
        jdbc.update("""
                DELETE FROM customers WHERE account_id IN
                    (SELECT id FROM accounts WHERE pending_expires_at < '2010-01-01')""");
        jdbc.update("DELETE FROM accounts WHERE pending_expires_at < '2010-01-01'");

        doAnswer(invocation -> jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, ?, 'ONLINE') RETURNING id
                """, Long.class, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                .when(customerApi).createOnlineProfile(any(), any(), any());
        doReturn(false).when(customerApi).flagLinkDecisionIfPhoneMatches(anyLong());
        doAnswer(invocation -> jdbc.update("DELETE FROM customers WHERE account_id = ?",
                (Object) invocation.getArgument(0)))
                .when(customerApi).deleteOnlineProfileOfUnverifiedAccount(anyLong());
    }

    // ---------------------------------------------------------------- xóa đúng, giữ đúng

    @Test
    void deletesExpiredPendingAccountWithOtpAndProfileAndKeepsTheRest() {
        String expired = register();
        long expiredId = accountId(expired);
        String verified = register();
        assertThat(verifyAccount(verified).getStatusCode()).isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofHours(1));
        String younger = register();
        long staff = staffAccount();
        clock.set(T0.plus(TTL));

        job.run();

        assertThat(rows("accounts", "id", expiredId)).as("tài khoản quá hạn").isZero();
        assertThat(rows("otp_tokens", "account_id", expiredId)).as("mã OTP").isZero();
        assertThat(rows("customers", "account_id", expiredId)).as("hồ sơ online").isZero();
        assertThat(status(younger)).as("còn 1 giờ").isEqualTo("PENDING");
        assertThat(rows("customers", "account_id", accountId(younger))).isEqualTo(1);
        assertThat(status(verified)).as("đã xác thực").isEqualTo("ACTIVE");
        assertThat(rows("accounts", "id", staff)).as("nhân viên").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?",
                Long.class, expiredId)).as("BR-QT-15: ST02 không ghi audit").isZero();
    }

    /** Quá hạn khi {@code now >= pending_expires_at} (cùng quy ước với hạn OTP). */
    @Test
    void accountIsKeptUntilExactlyItsExpiry() {
        String email = register();
        assertThat(pendingExpiresAt(email)).isEqualTo(T0.plus(TTL));

        clock.set(T0.plus(TTL).minusMillis(1));
        job.run();
        assertThat(status(email)).isEqualTo("PENDING");

        clock.set(T0.plus(TTL));
        job.run();
        assertThat(rows("accounts", "email", email)).isZero();
    }

    /** BR-TK-08 "để email có thể đăng ký lại"; verify sau khi bị xóa → 404. */
    @Test
    void emailCanRegisterAgainAndOldVerifyIsNotFoundAfterDeletion() {
        String email = register();
        String oldCode = code(email);
        clock.set(T0.plus(TTL));
        job.run();

        assertThat(verifyRaw(email, oldCode).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(register(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(status(email)).isEqualTo("PENDING");
    }

    /** BR-QT-13: đổi [CFG] chỉ áp dụng cho tài khoản đăng ký sau đó; tài khoản cũ giữ hạn đã chốt. */
    @Test
    void ttlChangeOnlyAffectsAccountsRegisteredAfterIt() {
        String before = register();
        try {
            jdbc.update("UPDATE system_configs SET value = '48' WHERE key = 'account.pending_ttl_hours'");
            configs.reload();
            String after = register();

            assertThat(pendingExpiresAt(before)).isEqualTo(T0.plus(TTL));
            assertThat(pendingExpiresAt(after)).isEqualTo(T0.plus(Duration.ofHours(48)));

            clock.set(T0.plus(TTL));
            job.run();

            assertThat(rows("accounts", "email", before)).isZero();
            assertThat(status(after)).isEqualTo("PENDING");
        } finally {
            jdbc.update("UPDATE system_configs SET value = '24' WHERE key = 'account.pending_ttl_hours'");
            configs.reload();
        }
    }

    // ---------------------------------------------------------------- lỗi, lô

    /**
     * {@code CustomerApi} lỗi sau khi đã xóa hồ sơ: transaction của tài khoản đó rollback cả {@code otp_tokens} và hồ
     * sơ; tài khoản khác vẫn bị xóa; lượt sau xóa được.
     */
    @Test
    void failureRollsBackThatAccountOnlyAndNextRunRetries() {
        String failing = register();
        long failingId = accountId(failing);
        String other = register();
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            jdbc.update("DELETE FROM customers WHERE account_id = ?", id);
            if (id == failingId) {
                throw new UnsupportedOperationException("PendingAccountCleanupIT: customer lỗi");
            }
            return null;
        }).when(customerApi).deleteOnlineProfileOfUnverifiedAccount(anyLong());
        clock.set(T0.plus(TTL));

        job.run();

        assertThat(status(failing)).isEqualTo("PENDING");
        assertThat(rows("otp_tokens", "account_id", failingId)).as("otp_tokens rollback").isEqualTo(1);
        assertThat(rows("customers", "account_id", failingId)).as("hồ sơ rollback").isEqualTo(1);
        assertThat(rows("accounts", "email", other)).isZero();

        doAnswer(invocation -> jdbc.update("DELETE FROM customers WHERE account_id = ?",
                (Object) invocation.getArgument(0)))
                .when(customerApi).deleteOnlineProfileOfUnverifiedAccount(anyLong());
        job.run();
        assertThat(rows("accounts", "id", failingId)).as("lượt sau xóa bù").isZero();
    }

    @Test
    void deletesAcrossSeveralBatches() {
        List<String> emails = IntStream.range(0, 5).mapToObj(i -> register()).toList();
        clock.set(T0.plus(TTL));
        PendingAccountCleanupJob smallBatches = new PendingAccountCleanupJob(cleanup,
                new PendingAccountCleanupProperties("-", 2, Duration.ofSeconds(60), Duration.ofSeconds(1)), clock);

        smallBatches.run();

        assertThat(emails).allSatisfy(email -> assertThat(rows("accounts", "email", email)).isZero());
    }

    // ---------------------------------------------------------------- khóa, trùng lặp

    /**
     * Verify/resend đang giữ khóa dòng (ADR-0011): job bỏ qua thay vì chờ. Bỏ {@code SKIP LOCKED} thì job treo tới khi
     * khóa được nhả → hết 5 giây, đỏ.
     */
    @Test
    void skipsAccountLockedByConcurrentTransaction() throws Exception {
        String locked = register();
        String free = register();
        clock.set(T0.plus(TTL));
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM accounts WHERE email = ? FOR UPDATE", Long.class, locked);
                        rowLocked.countDown();
                        await(release);
                    }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(5), job::run);

            assertThat(status(locked)).isEqualTo("PENDING");
            assertThat(rows("accounts", "email", free)).isZero();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            job.run();
            assertThat(rows("accounts", "email", locked)).as("lượt sau xóa bù").isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * Verify commit giữa lúc job đọc id và lúc khóa: câu khóa kiểm lại điều kiện nên tài khoản (giờ đã {@code ACTIVE})
     * bị bỏ qua, không gọi {@code CustomerApi}, không xóa gì.
     */
    @Test
    void accountVerifiedAfterSelectionIsSkippedUnderLock() {
        String email = register();
        long id = accountId(email);
        clock.set(T0.plus(TTL));
        assertThat(cleanup.findExpiredIds(clock.instant(), 0, 100)).contains(id);

        clock.set(T0.plus(Duration.ofMinutes(1)));
        assertThat(verifyAccount(email).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(cleanup.purge(id, T0.plus(TTL))).isEqualTo(PendingAccountCleanupService.PurgeResult.SKIPPED);
        assertThat(status(email)).isEqualTo("ACTIVE");
        assertThat(rows("customers", "account_id", id)).isEqualTo(1);
        verify(customerApi, times(0)).deleteOnlineProfileOfUnverifiedAccount(id);
    }

    @Test
    void secondRunDeletesNothingAndCustomerApiIsCalledOncePerAccount() {
        List<Long> ids = IntStream.range(0, 3).mapToObj(i -> accountId(register())).toList();
        clock.set(T0.plus(TTL));

        job.run();
        job.run();

        ids.forEach(id -> verify(customerApi, times(1)).deleteOnlineProfileOfUnverifiedAccount(id));
    }

    /**
     * Hai lượt chạy song song (chạy tay trùng cron, hoặc nhiều instance): khóa {@code SKIP LOCKED} + kiểm lại dưới
     * khóa → mỗi tài khoản chỉ bị xóa và gọi {@code CustomerApi} đúng một lần. {@code CustomerApi} chậm 300 ms để hai
     * lượt thật sự chồng nhau.
     */
    @Test
    void concurrentRunsDeleteEachAccountExactlyOnce() throws Exception {
        List<Long> ids = IntStream.range(0, 4).mapToObj(i -> accountId(register())).toList();
        doAnswer(invocation -> {
            Thread.sleep(300);
            return jdbc.update("DELETE FROM customers WHERE account_id = ?", (Object) invocation.getArgument(0));
        }).when(customerApi).deleteOnlineProfileOfUnverifiedAccount(anyLong());
        clock.set(T0.plus(TTL));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = List.of(executor.submit(() -> {
                await(start);
                job.run();
            }), executor.submit(() -> {
                await(start);
                job.run();
            }));
            start.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        ids.forEach(id -> {
            assertThat(rows("accounts", "id", id)).isZero();
            verify(customerApi, times(1)).deleteOnlineProfileOfUnverifiedAccount(id);
        });
    }

    // ---------------------------------------------------------------- profile test không lên lịch job

    @Test
    void notScheduledInTestProfile() {
        assertThat(scheduledTasks.getScheduledTasks())
                .noneMatch(task -> task.toString().contains("PendingAccountCleanupJob"));
    }

    // ---------------------------------------------------------------- helpers

    private String register() {
        String email = "pending-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@petcare.test";
        assertThat(register(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return email;
    }

    private ResponseEntity<Map<String, Object>> register(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "abc12345");
        body.put("fullName", "Nguyễn Văn A");
        body.put("phone", "0901234567");
        body.put("isAdult", true);
        body.put("termsAccepted", true);
        return post("/api/auth/register", body);
    }

    private ResponseEntity<Map<String, Object>> verifyAccount(String email) {
        return verifyRaw(email, code(email));
    }

    private ResponseEntity<Map<String, Object>> verifyRaw(String email, String code) {
        return post("/api/auth/register/verify", Map.of("email", email, "code", code));
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json.writeValueAsString(body), headers), MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mã gốc mới nhất trong outbox (ST20 không chạy trong test). */
    private String code(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, String.class, email);
    }

    private long staffAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', 'RECEPTIONIST', 'ACTIVE') RETURNING id
                """, Long.class, "pending-staff-" + UUID.randomUUID() + "@petcare.test");
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", Long.class, email);
    }

    private String status(String email) {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE email = ?", String.class, email);
    }

    private Instant pendingExpiresAt(String email) {
        return jdbc.queryForObject("SELECT pending_expires_at FROM accounts WHERE email = ?", java.sql.Timestamp.class,
                email).toInstant();
    }

    private long rows(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
