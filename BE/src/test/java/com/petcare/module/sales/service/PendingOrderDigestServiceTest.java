package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.sales.repository.SalesOrderRepository;

/** ST13 báo Order PENDING quá hạn cho quản lý (BR-BH-05; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class PendingOrderDigestServiceTest {

    private static final long BRANCH = 3L;

    @Mock
    SalesOrderRepository orders;
    @Mock
    BranchQueryApi branches;
    @Mock
    NotificationApi notifications;
    @Mock
    ShiftNoticeRecipients recipients;

    PendingOrderDigestService service;

    @BeforeEach
    void setUp() {
        service = new PendingOrderDigestService(orders, branches, notifications, recipients);
    }

    @Test
    void sendsOneInAppNoticeToEveryRecipientWithEveryRequiredVariable() {
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh Q1", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false)));
        when(recipients.forBranch(BRANCH)).thenReturn(List.of(11L, 12L));

        // 2026-10-19 22:30 giờ Việt Nam
        service.notifyPendingOrders(BRANCH, 4, Instant.parse("2026-10-19T15:30:00Z"), 1);

        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(request.capture());
        NotificationRequest first = request.getAllValues().get(0);
        assertThat(first.templateCode()).isEqualTo("PENDING_ORDERS_DIGEST_APP");
        assertThat(first.channel()).isEqualTo(Channel.IN_APP);
        assertThat(first.recipientAccountId()).isEqualTo(11L);
        assertThat(first.payload().keySet()).isEqualTo(Set.of("chi_nhanh", "so_order", "so_ngay", "ngay_cu_nhat"));
        assertThat(first.payload()).containsEntry("chi_nhanh", "Chi nhánh Q1").containsEntry("so_order", "4")
                .containsEntry("so_ngay", "1").containsEntry("ngay_cu_nhat", "19/10/2026");
        assertThat(request.getAllValues().get(1).recipientAccountId()).isEqualTo(12L);
    }

    @Test
    void nobodyToNotifyIsNotAnError() {
        when(recipients.forBranch(BRANCH)).thenReturn(List.of());

        service.notifyPendingOrders(BRANCH, 2, Instant.parse("2026-10-19T15:30:00Z"), 1);

        verify(notifications, never()).enqueue(any());
    }
}
