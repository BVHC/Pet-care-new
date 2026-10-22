package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.customer.dto.CreateCounterCustomerRequest;
import com.petcare.module.customer.dto.CustomerSearchItemResponse;
import com.petcare.module.customer.dto.CustomerWriteResultResponse;
import com.petcare.module.customer.dto.UpdateCustomerRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.mapper.CustomerStaffMapper;
import com.petcare.module.customer.mapper.CustomerStaffMapperImpl;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class CounterCustomerServiceTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CustomerSearchService search = mock(CustomerSearchService.class);
    private final CustomerStaffMapper mapper = new CustomerStaffMapperImpl();
    private final BranchScope branchScope = mock(BranchScope.class);
    private final CounterCustomerService service = new CounterCustomerService(customers, search, mapper,
            branchScope);

    private static Customer withId(Customer customer, long id) {
        ReflectionTestUtils.setField(customer, "id", id);
        return customer;
    }

    private static Customer counterProfile() {
        return withId(Customer.createAtCounter("Nguyễn Thị Lan", "0901234567", "lan@x.vn", 5L), 30L);
    }

    private void actor(long accountId) {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(accountId);
        when(branchScope.current()).thenReturn(principal);
    }

    // ---------------------------------------------------------------- tạo

    @Test
    void createStoresACounterProfileByTheReceptionistWithLowercaseEmail() {
        actor(5L);
        when(customers.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0), 31L));
        when(search.findDuplicatePhoneProfiles("0901234567", 31L, CustomerWriteResultResponse.MAX_DUPLICATES))
                .thenReturn(List.of());

        CustomerWriteResultResponse result = service.createCounterCustomer(
                new CreateCounterCustomerRequest("  Nguyễn Thị Lan ", "0901234567", " Lan@Gmail.COM "));

        assertThat(result.customer().customerId()).isEqualTo(31L);
        assertThat(result.customer().fullName()).isEqualTo("Nguyễn Thị Lan");
        assertThat(result.customer().email()).isEqualTo("lan@gmail.com");
        assertThat(result.customer().createdChannel()).isEqualTo(CustomerChannel.COUNTER);
        assertThat(result.customer().hasAccount()).isFalse();
        assertThat(result.customer().linkDecisionPending()).isFalse();
        assertThat(result.duplicatePhoneProfiles()).isEmpty();
        verify(customers).saveAndFlush(Mockito.argThat(c -> c.getCreatedBy().equals(5L)
                && c.getAccountId() == null));
    }

    @Test
    void createWithBlankEmailStoresNoEmailAndReportsDuplicates() {
        actor(5L);
        when(customers.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0), 31L));
        CustomerSearchItemResponse other = new CustomerSearchItemResponse(9L, "Khác", "0901234567", "la***@x.vn",
                CustomerChannel.COUNTER, false, List.of());
        when(search.findDuplicatePhoneProfiles("0901234567", 31L, CustomerWriteResultResponse.MAX_DUPLICATES))
                .thenReturn(List.of(other));

        CustomerWriteResultResponse result = service.createCounterCustomer(
                new CreateCounterCustomerRequest("Lan", "0901234567", "  "));

        assertThat(result.customer().email()).isNull();
        assertThat(result.duplicatePhoneProfiles()).containsExactly(other);
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void updateLocksBeforeReadingAndNullKeepsEverything() {
        Customer customer = counterProfile();
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(customer));

        CustomerWriteResultResponse result = service.updateCustomer(30L, new UpdateCustomerRequest(null, null, null));

        assertThat(result.customer().fullName()).isEqualTo("Nguyễn Thị Lan");
        assertThat(result.customer().phone()).isEqualTo("0901234567");
        assertThat(result.customer().email()).isEqualTo("lan@x.vn");
        assertThat(result.duplicatePhoneProfiles()).isEmpty();
        InOrder order = inOrder(customers);
        order.verify(customers).findByIdForUpdate(30L);
        order.verify(customers).flush();
        verify(customers, never()).findById(any());
        verify(search, never()).findDuplicatePhoneProfiles(any(), any(), anyInt());
    }

    @Test
    void updateChangedPhoneChecksDuplicatesButSamePhoneDoesNot() {
        Customer customer = counterProfile();
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(customer));
        CustomerSearchItemResponse other = new CustomerSearchItemResponse(9L, "Khác", "0912345678", null,
                CustomerChannel.ONLINE, true, List.of());
        when(search.findDuplicatePhoneProfiles(eq("0912345678"), eq(30L), anyInt())).thenReturn(List.of(other));

        CustomerWriteResultResponse changed = service.updateCustomer(30L,
                new UpdateCustomerRequest(null, "0912345678", null));
        assertThat(changed.customer().phone()).isEqualTo("0912345678");
        assertThat(changed.duplicatePhoneProfiles()).containsExactly(other);

        Mockito.clearInvocations(search);
        CustomerWriteResultResponse same = service.updateCustomer(30L,
                new UpdateCustomerRequest("Tên mới", "0912345678", null));
        assertThat(same.customer().fullName()).isEqualTo("Tên mới");
        assertThat(same.duplicatePhoneProfiles()).isEmpty();
        verify(search, never()).findDuplicatePhoneProfiles(any(), any(), anyInt());
    }

    @Test
    void updateEmptyEmailClearsAnUnlinkedProfileEmail() {
        Customer customer = counterProfile();
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(customer));

        CustomerWriteResultResponse result = service.updateCustomer(30L, new UpdateCustomerRequest(null, null, ""));

        assertThat(result.customer().email()).isNull();
    }

    @Test
    void updateCounterProfileCannotClearThePhone() {
        Customer customer = counterProfile();
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.updateCustomer(30L, new UpdateCustomerRequest(null, "  ", null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-01");
        verify(customers, never()).flush();
    }

    @Test
    void updateOnlineProfileMayClearThePhone() {
        Customer customer = withId(Customer.createOnline(10L, "Online", "0901234567", "acc@x.vn"), 40L);
        when(customers.findByIdForUpdate(40L)).thenReturn(Optional.of(customer));

        assertThat(service.updateCustomer(40L, new UpdateCustomerRequest(null, "", null)).customer().phone())
                .isNull();
    }

    @Test
    void updateEmailOfLinkedProfileIsRefusedButTheSameEmailIsAccepted() {
        Customer linked = counterProfile();
        linked.linkAccount(10L, "acc@x.vn");
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(linked));

        assertThatThrownBy(() -> service.updateCustomer(30L, new UpdateCustomerRequest(null, null, "khac@x.vn")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TK-16");
        verify(customers, never()).flush();

        assertThat(service.updateCustomer(30L, new UpdateCustomerRequest(null, null, "ACC@x.vn")).customer().email())
                .isEqualTo("acc@x.vn");
    }

    @Test
    void updateMissingCustomerIs404() {
        when(customers.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCustomer(99L, new UpdateCustomerRequest(null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void mapperIsGeneratedAndStandsAlone() {
        assertThat(Mappers.getMapper(CustomerStaffMapper.class)).isNotNull();
    }
}
