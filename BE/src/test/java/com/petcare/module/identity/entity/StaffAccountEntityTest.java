package com.petcare.module.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.Role;

/**
 * Tài khoản#4 và gửi lại mật khẩu ban đầu trên entity (BR-QT-02, BR-TK-01, 17; docs/adr/0030): giá trị hệ thống tự đặt
 * khi tạo, những gì {@code reissueInitialPassword} xóa / giữ, và các lời gọi sai bị chặn.
 */
class StaffAccountEntityTest {

    private static final Instant T0 = Instant.parse("2026-10-11T02:00:00Z");

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void createStaffIsActiveMustChangePasswordAndKeepsPhone(Role role) {
        Account account = Account.createStaff("nv@petcare.test", "0901234567", "$2a$hash", role);

        assertThat(account.getEmail()).isEqualTo("nv@petcare.test");
        assertThat(account.getPhone()).isEqualTo("0901234567");
        assertThat(account.getPasswordHash()).isEqualTo("$2a$hash");
        assertThat(account.getRole()).isEqualTo(role);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(account.isLocked()).isFalse();
        assertThat(account.getPendingExpiresAt()).as("CHECK ck_accounts_pending_expiry: chỉ PENDING có hạn").isNull();
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLastSeenAt()).isNull();
    }

    @Test
    void createStaffRejectsCustomerRoleAndMissingPhone() {
        assertThatThrownBy(() -> Account.createStaff("a@b.c", "0901234567", "h", Role.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.createStaff("a@b.c", null, "h", Role.VET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reissueInitialPasswordReplacesHashAndClearsCounterTempLockAndLastSeen() {
        Account account = Account.createStaff("nv@petcare.test", "0901234567", "old-hash", Role.VET);
        account.recordFailedLogin(T0, Duration.ofMinutes(15), 5, Duration.ofMinutes(15));
        ReflectionTestUtils.setField(account, "lockedUntil", T0.plusSeconds(600));
        ReflectionTestUtils.setField(account, "lastSeenAt", T0);
        ReflectionTestUtils.setField(account, "locked", true);

        account.reissueInitialPassword("new-hash");

        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getLastSeenAt()).isNull();
        assertThat(account.isMustChangePassword()).as("vẫn bắt đổi ở lần đăng nhập đầu").isTrue();
        assertThat(account.isLocked()).as("khóa của ADMIN không đổi (BR-QT-11)").isTrue();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void reissueInitialPasswordRefusesDisabledOrAlreadyChangedOrCustomer() {
        Account disabled = Account.createStaff("a@b.c", "0901234567", "h", Role.VET);
        ReflectionTestUtils.setField(disabled, "status", AccountStatus.DISABLED);
        Account changed = Account.createStaff("a@b.c", "0901234567", "h", Role.VET);
        changed.changePassword("h2", T0);
        Account customer = Account.registerCustomer("c@b.c", "h", T0);
        customer.verify();

        for (Account account : new Account[] {disabled, changed, customer}) {
            String before = account.getPasswordHash();
            assertThatThrownBy(() -> account.reissueInitialPassword("x")).isInstanceOf(IllegalStateException.class);
            assertThat(account.getPasswordHash()).isEqualTo(before);
        }
    }

    @Test
    void staffProfileCreateSetsOnlyIdentityNameAndBranch() {
        StaffProfile profile = StaffProfile.create(9L, "Bác sĩ An", 3L);

        assertThat(profile.getAccountId()).isEqualTo(9L);
        assertThat(profile.getFullName()).isEqualTo("Bác sĩ An");
        assertThat(profile.getBranchId()).isEqualTo(3L);
        assertThat(profile.getAvatarUrl()).isNull();
        assertThat(profile.getSpecialty()).isNull();
        assertThat(profile.getBio()).isNull();
        assertThat(StaffProfile.create(10L, "Quản lý chuỗi", null).getBranchId()).as("SUPER_MANAGER").isNull();
    }

    @Test
    void staffProfileCreateRejectsMissingAccountOrBlankName() {
        assertThatThrownBy(() -> StaffProfile.create(null, "A", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaffProfile.create(1L, "  ", 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
