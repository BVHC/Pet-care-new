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
import com.petcare.module.customer.api.CustomerQueryApi.LinkCandidate;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
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
class CustomerQueryServiceTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final PetRepository pets = mock(PetRepository.class);
    private final AppointmentQueryApi appointments = mock(AppointmentQueryApi.class);
    private final BoardingQueryApi boardings = mock(BoardingQueryApi.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final FeedbackQueryApi feedbacks = mock(FeedbackQueryApi.class);
    private final CustomerQueryService service = new CustomerQueryService(customers,
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

    @Test
    void linkCandidatesMaskTheNameAndReportEmailPresence() {
        Customer withEmail = withId(Customer.createAtCounter("Nguyễn Văn A", "0901234567", "a@x.vn", 5L), 1L);
        Customer withoutEmail = withId(Customer.createAtCounter("Lê Hoa", "0901234567", null, 5L), 2L);
        when(customers.findUnlinkedCounterProfilesByPhone("0901234567")).thenReturn(List.of(withEmail, withoutEmail));

        assertThat(service.findLinkCandidates("0901234567")).containsExactly(
                new LinkCandidate(1L, "Ng*** V** A", true), new LinkCandidate(2L, "Lê*** H", false));
        assertThat(service.findLinkCandidates(" ")).isEmpty();
        assertThat(service.findLinkCandidates(null)).isEmpty();
    }

    @Test
    void linkabilityFollowsChannelAndData() {
        Customer onlineProfile = withId(online(10L, null), 20L);
        Customer linked = withId(counter("a@x.vn"), 21L);
        linked.linkAccount(11L, "a@x.vn");
        when(customers.findByAccountId(10L)).thenReturn(Optional.of(onlineProfile));
        when(customers.findByAccountId(11L)).thenReturn(Optional.of(linked));

        assertThat(service.checkOnlineProfileLinkable(10L)).isEqualTo(OnlineProfileLinkability.LINKABLE);
        assertThat(service.checkOnlineProfileLinkable(11L)).isEqualTo(OnlineProfileLinkability.ALREADY_LINKED);
        when(orders.existsByCustomer(20L)).thenReturn(true);
        assertThat(service.checkOnlineProfileLinkable(10L)).isEqualTo(OnlineProfileLinkability.HAS_DATA);
    }

}
