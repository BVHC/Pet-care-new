package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.shop.entity.OnlineOrder;

/** Người nhận thông báo đơn online theo BR-TB-02 (docs/adr/0146): tài khoản đã kích hoạt, tài khoản PENDING, không kênh nào. */
class OnlineOrderNoticesTest {

    private static final long CUSTOMER = 9L;
    private static final long ACCOUNT = 77L;

    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final OnlineOrderNotices notices = new OnlineOrderNotices(notifications, customers, accounts, branches);
    private OnlineOrder order;

    @BeforeEach
    void setUp() {
        order = OnlineOrder.place("DO-2611-00001", 500L, CUSTOMER, 3L, "An", "0901", "12 Lê Lợi", 30_000,
                Instant.parse("2026-11-15T03:00:00Z"));
        ReflectionTestUtils.setField(order, "id", 600L);
    }

    private void contact(Long accountId, String profileEmail) {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(
                new CustomerContact(CUSTOMER, "Khách An", "0901", profileEmail, accountId, false)));
    }

    @Test
    void anActiveAccountGetsInAppAndEmailToTheAccountEmail() {
        contact(ACCOUNT, "profile@example.com");
        when(accounts.findContacts(anyCollection())).thenReturn(List.of(account(false)));

        notices.cancelled(order, "Quá hạn thanh toán");

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(sent.capture());
        assertThat(sent.getAllValues()).extracting(NotificationRequest::templateCode, NotificationRequest::channel,
                NotificationRequest::recipientAccountId, NotificationRequest::recipientEmail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ONLINE_ORDER_CANCELLED_APP", Channel.IN_APP, ACCOUNT, null),
                        org.assertj.core.groups.Tuple.tuple("ONLINE_ORDER_CANCELLED", Channel.EMAIL, null,
                                "account@example.com"));
        assertThat(sent.getAllValues().get(0).payload()).containsEntry("ma_don", "DO-2611-00001")
                .containsEntry("ly_do", "Quá hạn thanh toán").containsEntry("ten_khach", "Khách An");
    }

    @Test
    void aPendingAccountGetsEmailOnlyToTheProfileEmail() {
        contact(ACCOUNT, "profile@example.com");
        when(accounts.findContacts(anyCollection())).thenReturn(List.of(account(true)));

        notices.cancelled(order, "Quá hạn thanh toán");

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(sent.capture());
        assertThat(sent.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.getValue().recipientEmail()).isEqualTo("profile@example.com");
        assertThat(sent.getValue().recipientAccountId()).isNull();
    }

    @Test
    void aCustomerWithoutAnyChannelGetsNothingAndNoError() {
        contact(null, null);

        notices.cancelled(order, "Quá hạn thanh toán");

        verify(notifications, never()).enqueue(any());
    }

    @Test
    void aBlankProfileEmailAndNoAccountIsAlsoNoChannel() {
        contact(null, "  ");

        notices.cancelled(order, "Quá hạn thanh toán");

        verify(notifications, never()).enqueue(any());
    }

    private static AccountContact account(boolean pending) {
        return new AccountContact(ACCOUNT, "account@example.com", pending);
    }
}
