package com.petcare.module.customer.service;

import static com.petcare.module.customer.service.CustomerTestData.customer;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.dto.UpdateMyCustomerProfileRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.mapper.CustomerProfileMapper;
import com.petcare.module.customer.mapper.CustomerProfileMapperImpl;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC06 — hồ sơ khách của tôi (BR-TK-15, BR-KH-01; docs/adr/0028): email bị từ chối trước mọi đọc, khóa hồ sơ trước khi
 * đọc / ghi, null = giữ / rỗng = xóa, hồ sơ tại quầy không xóa được SĐT, cờ chờ liên kết không đổi, email trả về là email
 * tài khoản, flush trước khi map. Mapper thật; so cả record để bắt trường lấy nhầm nguồn.
 */
class MyCustomerProfileServiceTest {

    private static final long ACCOUNT = 7L;
    private static final String ACCOUNT_EMAIL = "khach@petcare.test";

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final MyCustomerLookup lookup = spy(new MyCustomerLookup(customers));
    private final BranchScope branchScope = mock(BranchScope.class);
    private final CustomerProfileMapper mapper = spy(new CustomerProfileMapperImpl());
    private final MyCustomerProfileService service = new MyCustomerProfileService(customers, lookup, branchScope,
            mapper);

    @BeforeEach
    void setUp() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(principal.email()).thenReturn(ACCOUNT_EMAIL);
        when(branchScope.current()).thenReturn(principal);
    }

    // ---------------------------------------------------------------- GET

    @Test
    void getReturnsProfileWithAccountEmailNotProfileEmail() {
        when(customers.findByAccountId(ACCOUNT)).thenReturn(Optional.of(
                customer(11L, ACCOUNT, CustomerChannel.ONLINE, "Nguyễn An", "0901234567", null,
                        "https://img/a.png", true)));

        assertThat(service.getMyProfile()).isEqualTo(new CustomerProfileResponse(11L, "Nguyễn An", "0901234567",
                ACCOUNT_EMAIL, "https://img/a.png", CustomerChannel.ONLINE, true, true));
        verify(customers, never()).findByAccountIdForUpdate(any());
    }

    // ---------------------------------------------------------------- PATCH thành công

    @Test
    void updatesEveryFieldAfterStrip() {
        Customer online = lockedOwner(CustomerChannel.ONLINE, "Tên cũ", null, null);

        CustomerProfileResponse response = service.updateMyProfile(
                request("  Nguyễn An  ", "0901234567", " https://img/a.png ", null));

        assertThat(response).isEqualTo(new CustomerProfileResponse(11L, "Nguyễn An", "0901234567", ACCOUNT_EMAIL,
                "https://img/a.png", CustomerChannel.ONLINE, true, false));
        assertThat(online.getFullName()).isEqualTo("Nguyễn An");
    }

    @Test
    void nullFieldsKeepCurrentValues() {
        lockedOwner(CustomerChannel.ONLINE, "Nguyễn An", "0901234567", "https://img/a.png");

        assertThat(service.updateMyProfile(request(null, null, null, null))).isEqualTo(new CustomerProfileResponse(
                11L, "Nguyễn An", "0901234567", ACCOUNT_EMAIL, "https://img/a.png", CustomerChannel.ONLINE, true,
                false));
    }

    @Test
    void emptyOrBlankClearsPhoneAndAvatarOfOnlineProfile() {
        lockedOwner(CustomerChannel.ONLINE, "Nguyễn An", "0901234567", "https://img/a.png");

        CustomerProfileResponse response = service.updateMyProfile(request(null, "", "   ", null));

        assertThat(response.phone()).isNull();
        assertThat(response.avatarUrl()).isNull();
        assertThat(response.fullName()).isEqualTo("Nguyễn An");
    }

    @Test
    void linkedCounterProfileKeepsChannelAndCanChangePhone() {
        lockedOwner(CustomerChannel.COUNTER, "Tên quầy", "0911111111", null);

        CustomerProfileResponse response = service.updateMyProfile(request("Tên mới", "0922222222", null, null));

        assertThat(response.createdChannel()).isEqualTo(CustomerChannel.COUNTER);
        assertThat(response.phone()).isEqualTo("0922222222");
    }

    @Test
    void linkDecisionFlagIsNotTouched() {
        Customer online = customer(11L, ACCOUNT, CustomerChannel.ONLINE, "Tên", "0901234567", null, null, true);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(online));

        assertThat(service.updateMyProfile(request(null, "0909999999", null, null)).linkDecisionPending()).isTrue();
        assertThat(online.isLinkDecisionPending()).isTrue();
    }

    // ---------------------------------------------------------------- BR-TK-15, BR-KH-01

    @Test
    void emailRejectedBeforeAnyRead() {
        assertThatThrownBy(() -> service.updateMyProfile(request("Tên", null, null, "new@x.test")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-TK-15");
                    assertThat(ex.getMessage())
                            .isEqualTo("Không thể tự sửa email; liên hệ lễ tân để được sửa hộ (BR-TK-15)");
                });
        verifyNoInteractions(customers, mapper);
    }

    @Test
    void counterProfileCannotClearPhoneAndNothingChanges() {
        Customer counter = lockedOwner(CustomerChannel.COUNTER, "Tên quầy", "0911111111", null);

        assertThatThrownBy(() -> service.updateMyProfile(request("Tên mới", "", null, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-KH-01");
                    assertThat(ex.getMessage()).isEqualTo("Hồ sơ tại quầy bắt buộc có số điện thoại (BR-KH-01)");
                });
        assertThat(counter.getFullName()).isEqualTo("Tên quầy");
        assertThat(counter.getPhone()).isEqualTo("0911111111");
        verify(customers, never()).flush();
    }

    // ---------------------------------------------------------------- khóa, flush

    @Test
    void locksBeforeWritingAndFlushesBeforeMapping() {
        lockedOwner(CustomerChannel.ONLINE, "Tên", null, null);

        service.updateMyProfile(request("Tên mới", null, null, null));

        InOrder order = inOrder(customers, mapper);
        order.verify(customers).findByAccountIdForUpdate(ACCOUNT);
        order.verify(customers).flush();
        order.verify(mapper).toResponse(any(), any());
        verify(customers, never()).findByAccountId(ACCOUNT);
    }

    @Test
    void transactionBoundaries() throws NoSuchMethodException {
        Transactional get = MyCustomerProfileService.class.getMethod("getMyProfile")
                .getAnnotation(Transactional.class);
        Transactional patch = MyCustomerProfileService.class
                .getMethod("updateMyProfile", UpdateMyCustomerProfileRequest.class).getAnnotation(Transactional.class);

        assertThat(get.readOnly()).isTrue();
        assertThat(patch.readOnly()).isFalse();
        assertThat(patch.noRollbackFor()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private Customer lockedOwner(CustomerChannel channel, String fullName, String phone, String avatarUrl) {
        Customer owner = customer(11L, ACCOUNT, channel, fullName, phone, "ho-so@petcare.test", avatarUrl, false);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(owner));
        return owner;
    }

    private static UpdateMyCustomerProfileRequest request(String fullName, String phone, String avatarUrl,
            String email) {
        return new UpdateMyCustomerProfileRequest(fullName, phone, avatarUrl, email);
    }
}
