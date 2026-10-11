package com.petcare.module.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.Role;

/**
 * BR-TK-09 (ST01) trên {@link Account} — cửa sổ cố định tính từ lần sai đầu, chạm ngưỡng thì khóa tạm, chỉ đăng nhập
 * thành công mới xóa bộ đếm (docs/adr/0019 mục 2). Tham số [CFG] mặc định V2: cửa sổ 15 phút, 5 lần, khóa 15 phút.
 */
class AccountTest {

    private static final Instant T0 = Instant.parse("2026-10-08T02:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final int MAX = 5;

    // ---------------------------------------------------------------- bộ đếm

    @Test
    void firstFailureStartsWindow() {
        Account account = account();

        assertThat(account.recordFailedLogin(T0, WINDOW, MAX, LOCK)).isFalse();

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getFirstFailedLoginAt()).isEqualTo(T0);
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void failuresInsideWindowAccumulateUntilFourth() {
        Account account = account();
        for (int i = 0; i < 4; i++) {
            assertThat(account.recordFailedLogin(T0.plusSeconds(60L * i), WINDOW, MAX, LOCK)).isFalse();
        }

        assertThat(account.getFailedLoginCount()).isEqualTo(4);
        assertThat(account.getFirstFailedLoginAt()).as("cửa sổ cố định: mốc là lần sai đầu").isEqualTo(T0);
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void fifthFailureInsideWindowLocksAndResetsCounter() {
        Account account = withCounter(4, T0);
        Instant now = T0.plus(WINDOW).minusMillis(1);

        assertThat(account.recordFailedLogin(now, WINDOW, MAX, LOCK)).isTrue();

        assertThat(account.getLockedUntil()).isEqualTo(now.plus(LOCK));
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
    }

    @Test
    void failureExactlyAtWindowEndStartsNewWindow() {
        Account account = withCounter(4, T0);
        Instant now = T0.plus(WINDOW);

        assertThat(account.recordFailedLogin(now, WINDOW, MAX, LOCK)).as("now − first = window: cửa sổ mới").isFalse();

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getFirstFailedLoginAt()).isEqualTo(now);
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void thresholdFollowsGivenMaxAttempts() {
        Account account = withCounter(2, T0);

        assertThat(account.recordFailedLogin(T0.plusSeconds(1), WINDOW, 3, LOCK)).isTrue();
        assertThat(account.getLockedUntil()).isEqualTo(T0.plusSeconds(1).plus(LOCK));
    }

    @Test
    void lockDurationIsSnapshottedIntoRow() {
        Account account = withCounter(4, T0);

        account.recordFailedLogin(T0.plusSeconds(5), WINDOW, MAX, Duration.ofMinutes(40));

        assertThat(account.getLockedUntil()).isEqualTo(T0.plusSeconds(5).plus(Duration.ofMinutes(40)));
    }

    // ---------------------------------------------------------------- khóa tạm

    @Test
    void temporaryLockBoundaryIsExclusive() {
        Account account = account();
        ReflectionTestUtils.setField(account, "lockedUntil", T0);

        assertThat(account.isTemporarilyLocked(T0.minusMillis(1))).isTrue();
        assertThat(account.isTemporarilyLocked(T0)).as("đúng mốc locked_until là đã hết khóa").isFalse();
        assertThat(account.isTemporarilyLocked(T0.plusMillis(1))).isFalse();
        assertThat(account().isTemporarilyLocked(T0)).isFalse();
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void successResetsCounterAndClearsExpiredLock() {
        Account account = withCounter(3, T0);
        ReflectionTestUtils.setField(account, "lockedUntil", T0.minusSeconds(1));

        account.recordSuccessfulLogin(T0);

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void successClearsLockEndingExactlyNow() {
        Account account = account();
        ReflectionTestUtils.setField(account, "lockedUntil", T0);

        account.recordSuccessfulLogin(T0);

        assertThat(account.getLockedUntil()).isNull();
    }

    // ---------------------------------------------------------------- đổi mật khẩu (BR-TK-14, 17; docs/adr/0022)

    @Test
    void changePasswordSetsHashClearsMustChangeAndCounter() {
        Account account = withCounter(3, T0);
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        ReflectionTestUtils.setField(account, "lockedUntil", T0.minusSeconds(1));

        account.changePassword("new-hash", T0);

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isMustChangePassword()).isFalse();
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        assertThat(account.getLockedUntil()).as("khóa đã hết hạn bị xóa").isNull();
    }

    @Test
    void changePasswordDoesNotTouchStatusOrAdminLock() {
        Account account = account();
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);

        account.changePassword("new-hash", T0);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isLocked()).isFalse();
    }

    // ---------------------------------------------------------------- đặt lại mật khẩu (BR-TK-13; docs/adr/0023)

    @Test
    void resetPasswordClearsActiveTemporaryLockCounterMustChangeAndOnlineStatus() {
        Account account = withCounter(3, T0);
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        ReflectionTestUtils.setField(account, "lockedUntil", T0.plus(LOCK));
        ReflectionTestUtils.setField(account, "lastSeenAt", T0.minusSeconds(30));

        account.resetPassword("new-hash");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isMustChangePassword()).isFalse();
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        assertThat(account.getLockedUntil()).as("BR-TK-13: gỡ khóa tạm còn hạn").isNull();
        assertThat(account.getLastSeenAt()).as("BR-TN-06: mọi phiên bị hủy nên offline ngay").isNull();
    }

    @Test
    void resetPasswordDoesNotTouchStatusOrAdminLock() {
        Account account = account();
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);

        account.resetPassword("new-hash");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isLocked()).isFalse();
    }

    // ---------------------------------------------------------------- SĐT nhân viên (UC06, BR-TK-01)

    @Test
    void changeStaffPhoneReplacesPhoneOnly() {
        Account account = staff("0901234567");

        account.changeStaffPhone("0987654321");

        assertThat(account.getPhone()).isEqualTo("0987654321");
        assertThat(account.getRole()).isEqualTo(Role.VET);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void changeStaffPhoneRejectsNull() {
        Account account = staff("0901234567");

        assertThatThrownBy(() -> account.changeStaffPhone(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-TK-01");
        assertThat(account.getPhone()).isEqualTo("0901234567");
    }

    @Test
    void changeStaffPhoneRejectsCustomerAccount() {
        Account customer = account();

        assertThatThrownBy(() -> customer.changeStaffPhone("0987654321")).isInstanceOf(IllegalStateException.class);
        assertThat(customer.getPhone()).isNull();
    }

    private static Account staff(String phone) {
        Account account = account();
        ReflectionTestUtils.setField(account, "role", Role.VET);
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "pendingExpiresAt", null);
        ReflectionTestUtils.setField(account, "phone", phone);
        return account;
    }

    private static Account account() {
        return Account.registerCustomer("khach@petcare.test", "hash", T0.plus(Duration.ofHours(24)));
    }

    private static Account withCounter(int count, Instant first) {
        Account account = account();
        ReflectionTestUtils.setField(account, "failedLoginCount", count);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", first);
        return account;
    }
}
