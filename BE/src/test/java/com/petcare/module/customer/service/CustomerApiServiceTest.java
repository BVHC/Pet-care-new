package com.petcare.module.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.content.api.FeedbackQueryApi;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.repository.AddressRepository;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * UC07 / UC01 / ST02 phía customer (docs/adr/0027, 0041): thứ tự khóa, kiểm lại dưới khóa, chỉ ném BR-TK-19, mọi lần
 * từ chối trước lệnh ghi đầu tiên.
 */
class CustomerApiServiceTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final PetRepository pets = mock(PetRepository.class);
    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class);
    private final BoardingQueryApi boardings = mock(BoardingQueryApi.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final FeedbackQueryApi feedbacks = mock(FeedbackQueryApi.class);
    private final CustomerApiService service = new CustomerApiService(customers, addresses,
            new CustomerDataProbe(pets, appointments, boardings, orders, feedbacks));

    private static Customer online(long accountId, String phone) {
        return Customer.createOnline(accountId, "Khách online", phone, null);
    }

    private static Customer counter(String email) {
        return Customer.createAtCounter("Khách quầy", "0901234567", email, 5L);
    }

    private static Customer withId(Customer customer, long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(customer, "id", id);
        return customer;
    }

    // ---------------------------------------------------------------- link

    @Test
    void linkLocksBothProfilesInAscendingIdOrderThenDeletesOnlineBeforeAssigning() {
        Customer online = withId(online(10L, "0901234567"), 20L);
        Customer counter = withId(counter("a@x.vn"), 30L);
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.of(20L));
        when(customers.findByIdForUpdate(20L)).thenReturn(Optional.of(online));
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(counter));

        service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn");

        InOrder order = inOrder(customers, addresses);
        order.verify(customers).findByIdForUpdate(20L);
        order.verify(customers).findByIdForUpdate(30L);
        order.verify(addresses).deleteByCustomer(20L);
        order.verify(customers).delete(online);
        order.verify(customers, org.mockito.Mockito.atLeastOnce()).flush();
        assertThat(counter.getAccountId()).isEqualTo(10L);
        assertThat(counter.getEmail()).isEqualTo("acc@x.vn");
        assertThat(counter.getPhone()).isEqualTo("0901234567");
    }

    @Test
    void linkLocksInAscendingOrderWhenCounterHasTheSmallerId() {
        Customer online = withId(online(10L, null), 40L);
        Customer counter = withId(counter("a@x.vn"), 30L);
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.of(40L));
        when(customers.findByIdForUpdate(40L)).thenReturn(Optional.of(online));
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(counter));

        service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn");

        InOrder order = inOrder(customers);
        order.verify(customers).findByIdForUpdate(30L);
        order.verify(customers).findByIdForUpdate(40L);
    }

    @Test
    void linkRefusesAndWritesNothing() {
        Customer online = withId(online(10L, null), 20L);
        Customer counter = withId(counter("a@x.vn"), 30L);
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.of(20L));
        when(customers.findByIdForUpdate(20L)).thenReturn(Optional.of(online));
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(counter));

        // email đã nhận mã khác email hiện tại của hồ sơ tại quầy
        assertRejected(() -> service.linkAccountToCounterProfile(10L, 30L, "other@x.vn", "acc@x.vn"));
        // hồ sơ tại quầy đã có tài khoản
        Customer taken = withId(counter("a@x.vn"), 31L);
        taken.linkAccount(99L, "a@x.vn");
        when(customers.findByIdForUpdate(31L)).thenReturn(Optional.of(taken));
        assertRejected(() -> service.linkAccountToCounterProfile(10L, 31L, "a@x.vn", "acc@x.vn"));
        // hồ sơ đích không tồn tại
        assertRejected(() -> service.linkAccountToCounterProfile(10L, 32L, "a@x.vn", "acc@x.vn"));
        // hồ sơ online đã phát sinh dữ liệu (mỗi nguồn dữ liệu một lần)
        when(pets.existsByCustomer(20L)).thenReturn(true);
        assertRejected(() -> service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn"));
        when(pets.existsByCustomer(20L)).thenReturn(false);
        when(feedbacks.existsByCustomer(20L)).thenReturn(true);
        assertRejected(() -> service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn"));

        verify(addresses, never()).deleteByCustomer(any());
        verify(customers, never()).delete(any());
        assertThat(counter.getAccountId()).isNull();
    }

    @Test
    void linkRefusesWhenAccountProfileIsAlreadyACounterProfile() {
        Customer linkedCounter = withId(counter("a@x.vn"), 20L);
        linkedCounter.linkAccount(10L, "a@x.vn");
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.of(20L));
        when(customers.findByIdForUpdate(20L)).thenReturn(Optional.of(linkedCounter));
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(withId(counter("b@x.vn"), 30L)));

        assertRejected(() -> service.linkAccountToCounterProfile(10L, 30L, "b@x.vn", "acc@x.vn"));
        verify(customers, never()).delete(any());
    }

    @Test
    void linkRefusesWhenTheLockedProfileNoLongerBelongsToTheAccount() {
        Customer replaced = withId(online(77L, null), 20L);
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.of(20L));
        when(customers.findByIdForUpdate(20L)).thenReturn(Optional.of(replaced));
        when(customers.findByIdForUpdate(30L)).thenReturn(Optional.of(withId(counter("a@x.vn"), 30L)));

        assertRejected(() -> service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn"));
    }

    @Test
    void linkOnlyEverThrowsBrTk19() {
        when(customers.findIdByAccountId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkAccountToCounterProfile(10L, 30L, "a@x.vn", "acc@x.vn"))
                .isExactlyInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TK-19");
    }

    // ---------------------------------------------------------------- tạo / cờ / xóa

    @Test
    void createOnlineProfileSavesOnlineProfileWithoutEmail() {
        Customer saved = withId(online(10L, "0901234567"), 5L);
        when(customers.saveAndFlush(any())).thenReturn(saved);

        assertThat(service.createOnlineProfile(10L, "Khách online", "0901234567")).isEqualTo(5L);

        verify(customers).saveAndFlush(org.mockito.ArgumentMatchers.argThat(c -> c.getAccountId().equals(10L)
                && c.getEmail() == null && c.getCreatedChannel().name().equals("ONLINE")));
    }

    @Test
    void flagIsSetOnlyWhenAnUnlinkedCounterProfileSharesThePhone() {
        Customer own = withId(online(10L, "0901234567"), 20L);
        when(customers.findByAccountIdForUpdate(10L)).thenReturn(Optional.of(own));
        when(customers.existsUnlinkedCounterProfileWithPhone("0901234567")).thenReturn(false);

        assertThat(service.flagLinkDecisionIfPhoneMatches(10L)).isFalse();

        when(customers.existsUnlinkedCounterProfileWithPhone("0901234567")).thenReturn(true);
        assertThat(service.flagLinkDecisionIfPhoneMatches(10L)).isTrue();
        assertThat(own.isLinkDecisionPending()).isTrue();
    }

    @Test
    void flagIsNotCheckedWithoutPhone() {
        Customer own = withId(online(10L, null), 20L);
        when(customers.findByAccountIdForUpdate(10L)).thenReturn(Optional.of(own));

        assertThat(service.flagLinkDecisionIfPhoneMatches(10L)).isFalse();

        verify(customers, never()).existsUnlinkedCounterProfileWithPhone(any());
    }

    @Test
    void deleteOnlineProfileRemovesAddressesBeforeTheProfileAndIgnoresMissing() {
        Customer own = withId(online(10L, null), 20L);
        when(customers.findByAccountIdForUpdate(10L)).thenReturn(Optional.of(own));

        service.deleteOnlineProfileOfUnverifiedAccount(10L);

        InOrder order = inOrder(addresses, customers);
        order.verify(addresses).deleteByCustomer(20L);
        order.verify(customers).delete(own);
        order.verify(customers).flush();

        when(customers.findByAccountIdForUpdate(11L)).thenReturn(Optional.empty());
        when(customers.findIdByAccountId(11L)).thenReturn(Optional.empty());
        service.deleteOnlineProfileOfUnverifiedAccount(11L);
        verify(customers, never()).delete(org.mockito.ArgumentMatchers.argThat(c -> c != own));
    }

    @Test
    void declineClearsTheFlagOfAnOnlineProfile() {
        Customer own = withId(online(10L, "0901234567"), 20L);
        own.flagLinkDecision();
        when(customers.findByAccountIdForUpdate(10L)).thenReturn(Optional.of(own));

        service.declineLink(10L);

        assertThat(own.isLinkDecisionPending()).isFalse();
    }

    // ---------------------------------------------------------------- hợp đồng giao dịch

    @Test
    void everyWriteJoinsTheCallersTransaction() {
        for (Method method : CustomerApi.class.getMethods()) {
            Method impl;
            try {
                impl = CustomerApiService.class.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
            Transactional tx = impl.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.noRollbackFor()).as("không nuốt lỗi: " + method.getName()).isEmpty();
        }
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isExactlyInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-TK-19");
    }
}
