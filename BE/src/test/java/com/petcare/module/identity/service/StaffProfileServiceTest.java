package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.dto.UpdateStaffProfileRequest;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.mapper.MeMapper;
import com.petcare.module.identity.mapper.MeMapperImpl;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * UC06 — nhân viên tự sửa hồ sơ (BR-TK-15, 20, 11; docs/adr/0026): thứ tự guard, null = giữ / rỗng = xóa, chỉ VET sửa
 * specialty/bio, độ dài bio theo [CFG] tính bằng code point, khóa {@code accounts} trước khi đọc hồ sơ, flush trước
 * khi map. Mapper thật; so cả record để bắt trường lấy nhầm nguồn.
 */
class StaffProfileServiceTest {

    private static final long ACCOUNT = 7L;
    private static final String EMAIL = "staff@petcare.test";
    private static final int BIO_MAX = 500;

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final MeMapper mapper = spy(new MeMapperImpl(Mappers.getMapper(LoginMapper.class)));
    private final StaffProfileService service = new StaffProfileService(accounts, staffProfiles, configs,
            branchScope, mapper);

    @BeforeEach
    void setUp() {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ACCOUNT, EMAIL, 42L, Role.VET, 3L, false));
        when(configs.getInt(ConfigKey.VET_BIO_MAX_LENGTH)).thenReturn(BIO_MAX);
    }

    // ---------------------------------------------------------------- sửa thành công

    @Test
    void vetUpdatesEveryFieldAndResponseTakesPhoneFromAccount() {
        Account account = given(Role.VET, "0901234567", profile("Tên cũ", "https://old/a.png", 3L, "Cũ", "Bio cũ"));

        StaffProfileResponse response = service.updateStaffProfile(request("  Bác sĩ An  ", " https://img/an.png ",
                "0987654321", " Nội khoa ", " Mười năm kinh nghiệm ", null));

        assertThat(response).isEqualTo(new StaffProfileResponse(ACCOUNT, "Bác sĩ An", "https://img/an.png",
                "0987654321", 3L, "Nội khoa", "Mười năm kinh nghiệm"));
        assertThat(account.getPhone()).isEqualTo("0987654321");
    }

    @Test
    void nullFieldsKeepCurrentValues() {
        Account account = given(Role.VET, "0901234567", profile("Bác sĩ An", "https://img/an.png", 3L, "Nội khoa",
                "Bio"));

        StaffProfileResponse response = service.updateStaffProfile(request(null, null, null, null, null, null));

        assertThat(response).isEqualTo(new StaffProfileResponse(ACCOUNT, "Bác sĩ An", "https://img/an.png",
                "0901234567", 3L, "Nội khoa", "Bio"));
        assertThat(account.getPhone()).isEqualTo("0901234567");
        verify(configs, never()).getInt(ConfigKey.VET_BIO_MAX_LENGTH);
    }

    @Test
    void emptyOrBlankClearsNullableFields() {
        given(Role.VET, "0901234567", profile("Bác sĩ An", "https://img/an.png", 3L, "Nội khoa", "Bio"));

        StaffProfileResponse response = service.updateStaffProfile(request(null, "", null, "   ", "", null));

        assertThat(response.avatarUrl()).isNull();
        assertThat(response.specialty()).isNull();
        assertThat(response.bio()).isNull();
        assertThat(response.fullName()).isEqualTo("Bác sĩ An");
    }

    @Test
    void nonVetUpdatesNameAvatarPhoneAndKeepsOldVetFields() {
        given(Role.CARETAKER, "0901234567", profile("Tên cũ", null, 3L, "Từ hồi làm VET", "Bio cũ"));

        StaffProfileResponse response = service.updateStaffProfile(
                request("Chăm sóc Bình", "https://img/b.png", "0911111111", null, null, null));

        assertThat(response).isEqualTo(new StaffProfileResponse(ACCOUNT, "Chăm sóc Bình", "https://img/b.png",
                "0911111111", 3L, "Từ hồi làm VET", "Bio cũ"));
    }

    @Test
    void adminWithoutBranchCanUpdate() {
        given(Role.ADMIN, "0907654321", profile("Quản trị", null, null, null, null));

        assertThat(service.updateStaffProfile(request("Quản trị viên", null, null, null, null, null)))
                .isEqualTo(new StaffProfileResponse(ACCOUNT, "Quản trị viên", null, "0907654321", null, null, null));
    }

    // ---------------------------------------------------------------- BR-TK-15

    @Test
    void emailRejectedBeforeAnyRead() {
        assertThatThrownBy(() -> service.updateStaffProfile(request("Tên", null, null, null, null, "new@x.test")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getRuleId()).isEqualTo("BR-TK-15"));
        verifyNoInteractions(accounts, staffProfiles, configs, mapper);
    }

    // ---------------------------------------------------------------- BR-TK-11 dưới khóa

    @Test
    void lockedUnderLockIs400BrTk11() {
        Account account = given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, null));
        ReflectionTestUtils.setField(account, "locked", true);

        assertRule(() -> service.updateStaffProfile(request("Tên mới", null, null, null, null, null)), "BR-TK-11");
        assertThat(account.getPhone()).isEqualTo("0901234567");
        verify(staffProfiles, never()).flush();
    }

    @Test
    void disabledUnderLockIs400BrTk11() {
        Account account = given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, null));
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);

        assertRule(() -> service.updateStaffProfile(request(null, null, "0911111111", null, null, null)), "BR-TK-11");
        assertThat(account.getPhone()).isEqualTo("0901234567");
    }

    // ---------------------------------------------------------------- chỉ VET (403)

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "CARETAKER"})
    void nonVetSpecialtyIs403(Role role) {
        StaffProfile profile = profile("Tên", null, 3L, null, null);
        given(role, "0901234567", profile);

        assertThatThrownBy(() -> service.updateStaffProfile(request("Tên mới", null, null, "Nội khoa", null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThat(profile.getFullName()).isEqualTo("Tên");
    }

    @Test
    void nonVetBioEvenEmptyIs403() {
        given(Role.CARETAKER, "0901234567", profile("Tên", null, 3L, null, null));

        assertThatThrownBy(() -> service.updateStaffProfile(request(null, null, null, null, "", null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ---------------------------------------------------------------- BR-TK-20

    @Test
    void bioAtLimitIsAcceptedAndOneMoreIsRejected() {
        StaffProfile profile = profile("Bác sĩ", null, 3L, null, "Bio cũ");
        given(Role.VET, "0901234567", profile);

        assertThat(service.updateStaffProfile(request(null, null, null, null, "a".repeat(BIO_MAX), null)).bio())
                .hasSize(BIO_MAX);
        assertRule(() -> service.updateStaffProfile(request(null, null, null, null, "a".repeat(BIO_MAX + 1), null)),
                "BR-TK-20");
    }

    @Test
    void bioLengthCountsCodePointsNotUtf16Units() {
        given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, null));
        String emojis = "🐶".repeat(BIO_MAX);
        assertThat(emojis.length()).isEqualTo(BIO_MAX * 2);

        assertThat(service.updateStaffProfile(request(null, null, null, null, emojis, null)).bio()).isEqualTo(emojis);
    }

    @Test
    void bioLimitComesFromConfigAndIsCheckedAfterStrip() {
        given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, null));
        when(configs.getInt(ConfigKey.VET_BIO_MAX_LENGTH)).thenReturn(100);

        assertThat(service.updateStaffProfile(request(null, null, null, null, "  " + "b".repeat(100) + "  ", null))
                .bio()).hasSize(100);
        assertThatThrownBy(() -> service.updateStaffProfile(request(null, null, null, null, "b".repeat(101), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Mô tả ngắn tối đa 100 ký tự (BR-TK-20)");
    }

    @Test
    void bioCheckedOnlyWhenSent() {
        given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, "x".repeat(400)));
        when(configs.getInt(ConfigKey.VET_BIO_MAX_LENGTH)).thenReturn(100);

        StaffProfileResponse response = service.updateStaffProfile(request("Tên mới", null, null, null, null, null));

        assertThat(response.fullName()).isEqualTo("Tên mới");
        assertThat(response.bio()).hasSize(400);
        verify(configs, never()).getInt(ConfigKey.VET_BIO_MAX_LENGTH);
    }

    // ---------------------------------------------------------------- khóa, flush, dữ liệu sai

    @Test
    void locksAccountBeforeReadingProfileAndFlushesBeforeMapping() {
        given(Role.VET, "0901234567", profile("Bác sĩ", null, 3L, null, null));

        service.updateStaffProfile(request("Tên mới", null, null, null, null, null));

        InOrder order = inOrder(accounts, staffProfiles, mapper);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(staffProfiles).findById(ACCOUNT);
        order.verify(staffProfiles).flush();
        order.verify(mapper).toStaffProfileResponse(any(), any());
        verify(accounts, never()).findById(ACCOUNT);
    }

    @Test
    void missingAccountIsIllegalState() {
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStaffProfile(request("Tên", null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(staffProfiles);
    }

    @Test
    void staffWithoutStaffProfileIsBadData() {
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account(Role.VET, "0901234567")));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStaffProfile(request("Tên", null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("staff_profiles");
    }

    @Test
    void isTransactional() throws NoSuchMethodException {
        Transactional tx = StaffProfileService.class
                .getMethod("updateStaffProfile", UpdateStaffProfileRequest.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    // ---------------------------------------------------------------- dữ liệu

    private Account given(Role role, String phone, StaffProfile profile) {
        Account account = account(role, phone);
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
        when(staffProfiles.findById(ACCOUNT)).thenReturn(Optional.of(profile));
        return account;
    }

    private static UpdateStaffProfileRequest request(String fullName, String avatarUrl, String phone,
            String specialty, String bio, String email) {
        return new UpdateStaffProfileRequest(fullName, avatarUrl, phone, specialty, bio, email);
    }

    private static void assertRule(ThrowingCallable call, String ruleId) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                ex -> assertThat(ex.getRuleId()).isEqualTo(ruleId));
    }

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
}
