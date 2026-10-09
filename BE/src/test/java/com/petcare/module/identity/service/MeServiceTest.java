package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.MeResponse;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.mapper.MeMapperImpl;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.security.BranchScope;

/**
 * {@code GET /api/me} (UC06, identity-v1 #8): nhân viên kèm hồ sơ (SĐT lấy từ tài khoản), khách kèm hồ sơ khách và cờ
 * chờ liên kết (BR-KH-01, BR-TK-19), nhân viên thiếu {@code staff_profiles} và khách thiếu hồ sơ là dữ liệu sai → lỗi.
 * Mapper thật; so cả record để bắt trường lấy nhầm nguồn.
 */
class MeServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long CUSTOMER = 800L;
    private static final String EMAIL = "me@petcare.test";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final MeService service = new MeService(accounts, staffProfiles, customers, branchScope,
            new MeMapperImpl(Mappers.getMapper(LoginMapper.class)));

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(
                new AccountPrincipal(ACCOUNT, EMAIL, 42L, Role.VET, 3L, false));
    }

    // ---------------------------------------------------------------- nhân viên

    @Test
    void staffGetsProfileWithPhoneFromAccountAndNoCustomerData() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.VET, "0901234567")));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.of(
                profile("Bác sĩ An", "https://img/an.png", 3L, "Nội khoa", "Mười năm kinh nghiệm")));

        MeResponse response = service.getMe();

        assertThat(response).isEqualTo(new MeResponse(
                new AccountSummary(ACCOUNT, EMAIL, Role.VET, AccountStatus.ACTIVE, false, false),
                new StaffProfileResponse(ACCOUNT, "Bác sĩ An", "https://img/an.png", "0901234567", 3L, "Nội khoa",
                        "Mười năm kinh nghiệm"),
                null, false));
        verifyNoInteractions(customers);
    }

    @Test
    void adminHasNoBranch() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.ADMIN, "0907654321")));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.of(profile("Quản trị", null, null, null, null)));

        MeResponse response = service.getMe();

        assertThat(response.staffProfile()).isEqualTo(
                new StaffProfileResponse(ACCOUNT, "Quản trị", null, "0907654321", null, null, null));
        assertThat(response.account().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void mustChangePasswordAndLockFlagsComeFromAccount() {
        Account account = account(Role.RECEPTIONIST, "0901111111");
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.of(profile("Lễ tân", null, 3L, null, null)));

        assertThat(service.getMe().account().mustChangePassword()).isTrue();
    }

    @Test
    void staffWithoutStaffProfileIsBadData() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.ADMIN, "0907654321")));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMe).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staff_profiles");
        verifyNoInteractions(customers);
    }

    // ---------------------------------------------------------------- khách (BR-KH-01, BR-TK-19)

    @Test
    void customerGetsCustomerIdAndPendingLinkFlag() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, null)));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(true)));

        MeResponse response = service.getMe();

        assertThat(response).isEqualTo(new MeResponse(
                new AccountSummary(ACCOUNT, EMAIL, Role.CUSTOMER, AccountStatus.ACTIVE, false, false),
                null, CUSTOMER, true));
        verifyNoInteractions(staffProfiles);
    }

    @Test
    void customerWithoutPendingLink() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, null)));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(false)));

        assertThat(service.getMe().linkDecisionPending()).isFalse();
    }

    @Test
    void customerWithoutProfileViolatesKh01() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, null)));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMe).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-KH-01");
    }

    @Test
    void customerProfileNotFound() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, null)));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMe).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customerQueryPlaceholderErrorPropagates() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, null)));
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenThrow(new UnsupportedOperationException("D010"));

        assertThatThrownBy(service::getMe).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- bất biến

    @Test
    void missingAccountIsIllegalState() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMe).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(staffProfiles, customers);
    }

    // ---------------------------------------------------------------- dữ liệu

    private static Account account(Role role, String phone) {
        Account account = Account.registerCustomer(EMAIL, "x", null);
        ReflectionTestUtils.setField(account, "id", ACCOUNT);
        ReflectionTestUtils.setField(account, "role", role);
        ReflectionTestUtils.setField(account, "phone", phone);
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        return account;
    }

    private static StaffProfile profile(String fullName, String avatarUrl, Long branchId, String specialty,
            String bio) {
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", ACCOUNT);
        ReflectionTestUtils.setField(profile, "fullName", fullName);
        ReflectionTestUtils.setField(profile, "avatarUrl", avatarUrl);
        ReflectionTestUtils.setField(profile, "branchId", branchId);
        ReflectionTestUtils.setField(profile, "specialty", specialty);
        ReflectionTestUtils.setField(profile, "bio", bio);
        return profile;
    }

    private static CustomerContact contact(boolean linkDecisionPending) {
        return new CustomerContact(CUSTOMER, "Nguyễn Văn A", "0912345678", EMAIL, ACCOUNT, linkDecisionPending);
    }
}
