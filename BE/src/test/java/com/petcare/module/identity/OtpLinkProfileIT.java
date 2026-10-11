package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.OtpService;
import com.petcare.module.identity.service.OtpService.ActiveOtp;
import com.petcare.support.MutableClock;

/**
 * OTP {@code LINK_PROFILE} trên Postgres 17 (docs/adr/0025): mã gắn đúng <b>tài khoản và hồ sơ</b>, hủy mã cũ theo tài
 * khoản, và bộ đếm sai được commit đúng — không chỉ nhờ {@code noRollbackFor}. Hai hồ sơ tại quầy A, B <b>cùng email</b>
 * ({@code customers.email} không duy nhất), hai tài khoản khách X, Y. Mỗi test dùng email riêng để BR-TK-07 (quota theo
 * email nhận) không lẫn giữa các test; giữa hai lần gửi tới cùng email thì dịch {@link MutableClock} 61 giây.
 * Phần endpoint (khóa {@code customers}, kiểm BR-TK-19 dưới khóa) thuộc phần 1.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OtpLinkProfileIT.TestBeans.class})
class OtpLinkProfileIT {

    private static final Instant T0 = Instant.parse("2026-10-09T03:00:00Z");
    private static final String WRONG = "BR-TK-05";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private OtpService otps;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String email;
    private long accountX;
    private long accountY;
    private long profileA;
    private long profileB;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        email = "link-it-" + UUID.randomUUID() + "@petcare.test";
        accountX = customerAccount();
        accountY = customerAccount();
        profileA = counterProfile(email);
        profileB = counterProfile(email);
    }

    // ------------------------------------------------------------------ đúng tài khoản và đúng hồ sơ

    @Test
    void issuedCodeIsBoundToAccountAndProfile() {
        long otpId = issue(accountX, profileA);

        assertThat(jdbc.queryForMap("SELECT account_id, customer_id, purpose, target_email FROM otp_tokens WHERE id = ?",
                otpId)).containsEntry("account_id", accountX).containsEntry("customer_id", profileA)
                .containsEntry("purpose", "LINK_PROFILE").containsEntry("target_email", email);
        assertThat(findActive(accountX, profileA)).hasValueSatisfying(active -> assertThat(active.id()).isEqualTo(otpId));
        assertThat(findActive(accountX, profileB)).as("hồ sơ khác cùng email").isEmpty();
        assertThat(findActive(accountY, profileA)).as("tài khoản khác cùng hồ sơ").isEmpty();
    }

    @Test
    void wrongAttemptForOtherProfileOrAccountDoesNotTouchTheCode() {
        long otpId = issue(accountX, profileA);

        assertThat(settle(accountX, profileB, otpId, false)).isEqualTo(WRONG);
        assertThat(settle(accountX, profileB, otpId, true)).isEqualTo(WRONG);
        assertThat(settle(accountY, profileA, otpId, true)).isEqualTo(WRONG);

        assertThat(failedAttempts(otpId)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class, otpId))
                .isTrue();
    }

    @Test
    void profileEmailChangeMakesTheOldCodeUnusable() {
        issue(accountX, profileA);
        String newEmail = "moi-" + email;
        jdbc.update("UPDATE customers SET email = ? WHERE id = ?", newEmail, profileA);

        assertThat(inTx(() -> otps.findActive(accountX, OtpPurpose.LINK_PROFILE, newEmail, profileA))).isEmpty();
    }

    // ------------------------------------------------------------------ Q5: hủy mã cũ theo tài khoản

    @Test
    void newCodeInvalidatesOnlyTheSameAccountsCodes() {
        long codeXA = issue(accountX, profileA);
        clock.advance(Duration.ofSeconds(61));
        long codeYA = issue(accountY, profileA);

        assertThat(invalidated(codeXA)).as("Y xin mã cho cùng hồ sơ không hủy được mã của X").isFalse();
        assertThat(findActive(accountX, profileA)).isPresent();

        clock.advance(Duration.ofSeconds(61));
        long codeXB = issue(accountX, profileB);
        assertThat(invalidated(codeXA)).as("X đổi sang hồ sơ khác: mã cũ của X bị hủy").isTrue();
        assertThat(invalidated(codeYA)).isFalse();
        assertThat(invalidated(codeXB)).isFalse();
    }

    // ------------------------------------------------------------------ bộ đếm sai được commit đúng

    /**
     * Điều kiện 1 (docs/adr/0025): overload có {@code customerId} mang {@code noRollbackFor}. Callback bắt
     * {@link OtpRejectedException} rồi return; nếu proxy đã đánh dấu rollback-only thì commit ném
     * {@code UnexpectedRollbackException} và test đỏ.
     */
    @Test
    void wrongCodeCommitsWithoutRollbackOnly() {
        long otpId = issue(accountX, profileA);

        assertThat(settle(accountX, profileA, otpId, false)).isEqualTo(WRONG);

        assertThat(failedAttempts(otpId)).isEqualTo(1);
    }

    /** Điều kiện 4: hai lần sai song song dưới khóa dòng {@code accounts} của X đều được đếm (không lost update). */
    @Test
    void concurrentWrongCodesAreBothCounted() throws Exception {
        long otpId = issue(accountX, profileA);
        CountDownLatch firstSettled = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    accounts.findByIdForUpdate(accountX).orElseThrow();
                    rejectQuietly(() -> otps.settleCheckedOtp(accountX, OtpPurpose.LINK_PROFILE, email, profileA,
                            otpId, false));
                    firstSettled.countDown();
                    await(releaseFirst);
                }));
        await(firstSettled);
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    accounts.findByIdForUpdate(accountX).orElseThrow();
                    rejectQuietly(() -> otps.settleCheckedOtp(accountX, OtpPurpose.LINK_PROFILE, email, profileA,
                            otpId, false));
                }));

        waitUntilBlockedOnAccountLock();
        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertThat(failedAttempts(otpId)).isEqualTo(2);
    }

    /** Điều kiện 5: mã bị thay giữa bước đọc (so BCrypt) và bước khóa thì lần sai không tính vào mã mới. */
    @Test
    void codeReplacedBetweenReadAndLockIsNotCounted() {
        long oldId = issue(accountX, profileA);
        clock.advance(Duration.ofSeconds(61));
        long newId = issue(accountX, profileA);

        assertThat(settle(accountX, profileA, oldId, false)).isEqualTo(WRONG);

        assertThat(failedAttempts(newId)).isZero();
        assertThat(invalidated(newId)).isFalse();
    }

    /** Điều kiện 6: lần sai thứ 5 hủy mã trong cùng lệnh ghi được commit; mã đúng sau đó vẫn bị từ chối. */
    @Test
    void fifthWrongCodeCancelsItAndTheRightCodeIsThenRejected() {
        long otpId = issue(accountX, profileA);

        for (int i = 1; i <= 4; i++) {
            assertThat(settle(accountX, profileA, otpId, false)).isEqualTo(WRONG);
        }
        assertThat(settle(accountX, profileA, otpId, false)).isEqualTo("BR-TK-06");
        assertThat(failedAttempts(otpId)).isEqualTo(5);
        assertThat(invalidated(otpId)).isTrue();

        assertThat(settle(accountX, profileA, otpId, true)).isEqualTo(WRONG);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class, otpId))
                .isTrue();
    }

    @Test
    void rightCodeForRightProfileIsConsumed() {
        long otpId = issue(accountX, profileA);

        assertThat(settle(accountX, profileA, otpId, true)).isNull();

        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                otpId)).isTrue();
    }

    @Test
    void linkProfileWithoutCustomerIsRejectedAndWritesNothing() {
        OtpService.PreparedOtp prepared = otps.prepare();

        assertThatThrownBy(() -> inTx(() -> otps.issuePrepared(accountX, OtpPurpose.LINK_PROFILE, email, prepared)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE target_email = ?", Integer.class, email))
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private long issue(long accountId, long customerId) {
        OtpService.PreparedOtp prepared = otps.prepare();
        inTx(() -> otps.issuePrepared(accountId, OtpPurpose.LINK_PROFILE, email, customerId, prepared));
        return jdbc.queryForObject("""
                SELECT max(id) FROM otp_tokens WHERE account_id = ? AND customer_id = ? AND purpose = 'LINK_PROFILE'
                """, Long.class, accountId, customerId);
    }

    private Optional<ActiveOtp> findActive(long accountId, long customerId) {
        return inTx(() -> otps.findActive(accountId, OtpPurpose.LINK_PROFILE, email, customerId));
    }

    /**
     * Một lần settle như use case sẽ làm: khóa dòng {@code accounts} rồi settle, trong một transaction; trả mã rule khi bị
     * từ chối ({@code null} khi mã được tiêu). Exception bị bắt <b>bên trong</b> callback để transaction vẫn commit —
     * commit ném {@code UnexpectedRollbackException} nếu có proxy nào đã đánh dấu rollback-only.
     */
    private String settle(long accountId, long customerId, long otpId, boolean matched) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            accounts.findByIdForUpdate(accountId).orElseThrow();
            try {
                otps.settleCheckedOtp(accountId, OtpPurpose.LINK_PROFILE, email, customerId, otpId, matched);
                return null;
            } catch (OtpRejectedException rejected) {
                return rejected.getMessage().substring(rejected.getMessage().lastIndexOf("(BR-") + 1,
                        rejected.getMessage().length() - 1);
            }
        });
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static void rejectQuietly(Runnable action) {
        try {
            action.run();
        } catch (OtpRejectedException expected) {
            // bộ đếm đã nằm trong persistence context; transaction vẫn commit (noRollbackFor)
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Hibernate 6 phát {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE} (CLAUDE.md, Gotchas). */
    private void waitUntilBlockedOnAccountLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("luồng thứ hai không chờ khóa dòng accounts");
    }

    private int failedAttempts(long otpId) {
        return jdbc.queryForObject("SELECT failed_attempts FROM otp_tokens WHERE id = ?", Integer.class, otpId);
    }

    private boolean invalidated(long otpId) {
        return jdbc.queryForObject("SELECT invalidated_at IS NOT NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                otpId);
    }

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "link-it-acc-" + UUID.randomUUID() + "@petcare.test");
    }

    private long counterProfile(String profileEmail) {
        return jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel)
                VALUES ('Khách tại quầy', '0901234567', ?, 'COUNTER') RETURNING id
                """, Long.class, profileEmail);
    }
}
