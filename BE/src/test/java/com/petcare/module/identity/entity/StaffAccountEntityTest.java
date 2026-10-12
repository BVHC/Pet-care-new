package com.petcare.module.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.Role;

/**
 * Tài khoản#4, gửi lại mật khẩu ban đầu, đổi chức vụ, điều chuyển trên entity (BR-QT-02, 03, 05, 06, BR-TK-01, 17, 20;
 * docs/adr/0030, 0031): giá trị hệ thống tự đặt khi tạo, những gì mỗi method đổi / giữ, và các lời gọi sai bị chặn.
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

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"BRANCH_MANAGER, VET", "VET, BRANCH_MANAGER", "RECEPTIONIST, CARETAKER", "CARETAKER, RECEPTIONIST"})
    void changeStaffRoleChangesOnlyRole(Role from, Role to) {
        Account account = Account.createStaff("nv@petcare.test", "0901234567", "hash", from);
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);
        ReflectionTestUtils.setField(account, "locked", true);
        ReflectionTestUtils.setField(account, "lastSeenAt", T0);

        account.changeStaffRole(to);

        assertThat(account.getRole()).isEqualTo(to);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(account.isLocked()).isTrue();
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(account.getPasswordHash()).isEqualTo("hash");
        assertThat(account.getLastSeenAt()).isEqualTo(T0);
        assertThat(account.getEmail()).isEqualTo("nv@petcare.test");
        assertThat(account.getPhone()).isEqualTo("0901234567");
    }

    @Test
    void changeStaffRoleRefusesRolesOutsideBranchStaff() {
        Account vet = Account.createStaff("a@b.c", "0901234567", "h", Role.VET);
        Account superManager = Account.createStaff("s@b.c", "0901234567", "h", Role.SUPER_MANAGER);
        Account customer = Account.registerCustomer("c@b.c", "h", T0);

        for (Role target : new Role[] {Role.ADMIN, Role.SUPER_MANAGER, Role.CUSTOMER, null}) {
            assertThatThrownBy(() -> vet.changeStaffRole(target)).as(String.valueOf(target))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> superManager.changeStaffRole(Role.VET)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.changeStaffRole(Role.VET)).isInstanceOf(IllegalStateException.class);
        assertThat(vet.getRole()).isEqualTo(Role.VET);
        assertThat(superManager.getRole()).isEqualTo(Role.SUPER_MANAGER);
    }

    @Test
    void transferToChangesOnlyBranchAndRejectsNull() {
        StaffProfile profile = StaffProfile.create(9L, "Bác sĩ An", 3L);
        profile.updateSelfProfile("Bác sĩ An", "https://img.test/a.png", "Nội khoa", "Mô tả");

        profile.transferTo(4L);

        assertThat(profile.getBranchId()).isEqualTo(4L);
        assertThat(profile.getFullName()).isEqualTo("Bác sĩ An");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://img.test/a.png");
        assertThat(profile.getSpecialty()).isEqualTo("Nội khoa");
        assertThat(profile.getBio()).isEqualTo("Mô tả");
        assertThatThrownBy(() -> profile.transferTo(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(profile.getBranchId()).isEqualTo(4L);
    }

    @Test
    void clearVetProfileClearsOnlySpecialtyAndBio() {
        StaffProfile profile = StaffProfile.create(9L, "Bác sĩ An", 3L);
        profile.updateSelfProfile("Bác sĩ An", "https://img.test/a.png", "Nội khoa", "Mô tả");

        profile.clearVetProfile();

        assertThat(profile.getSpecialty()).isNull();
        assertThat(profile.getBio()).isNull();
        assertThat(profile.getAvatarUrl()).isEqualTo("https://img.test/a.png");
        assertThat(profile.getFullName()).isEqualTo("Bác sĩ An");
        assertThat(profile.getBranchId()).isEqualTo(3L);
    }
}
