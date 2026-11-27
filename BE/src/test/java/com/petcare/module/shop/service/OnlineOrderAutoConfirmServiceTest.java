package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository.DueRow;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.Result;

/** ST12 (docs/adr/0146): khóa SKIP LOCKED trước, kiểm lại hạn dưới khóa, [CFG] đọc lúc chạy. */
class OnlineOrderAutoConfirmServiceTest {

    private static final long ONLINE_ID = 600L;
    private static final long ORDER_ID = 500L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");
    private static final Instant DELIVERED_8_DAYS_AGO = Instant.parse("2026-11-07T03:00:00Z");

    private final OnlineOrderRepository onlineOrders = mock(OnlineOrderRepository.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private OnlineOrderAutoConfirmService service;

    @BeforeEach
    void setUp() {
        when(onlineOrders.lockDeliveredSkipLocked(ONLINE_ID)).thenReturn(Optional.of(ONLINE_ID));
        when(onlineOrders.saveAndFlush(any(OnlineOrder.class))).thenAnswer(call -> call.getArgument(0));
        when(config.getInt(ConfigKey.SHOP_AUTO_CONFIRM_DAYS)).thenReturn(7);
        service = new OnlineOrderAutoConfirmService(onlineOrders, config, new OnlineOrderTransitionHandler(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OnlineOrder order(OnlineOrderStatus status, Instant deliveredAt) {
        OnlineOrder order = OnlineOrder.place("DO-2611-00001", ORDER_ID, 9L, 3L, "An", "0901", "12 Lê Lợi", 30_000, NOW);
        ReflectionTestUtils.setField(order, "id", ONLINE_ID);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "deliveredAt", deliveredAt);
        when(onlineOrders.findById(ONLINE_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static DueRow row(long id, Instant at) {
        DueRow row = mock(DueRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getDueAt()).thenReturn(at);
        return row;
    }

    @Test
    void confirmsADueOrderLockingFirst() {
        OnlineOrder order = order(OnlineOrderStatus.DELIVERED, DELIVERED_8_DAYS_AGO);

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.CONFIRMED);

        InOrder inOrder = inOrder(onlineOrders);
        inOrder.verify(onlineOrders).lockDeliveredSkipLocked(ONLINE_ID);
        inOrder.verify(onlineOrders).findById(ONLINE_ID);
        inOrder.verify(onlineOrders).saveAndFlush(order);
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isEqualTo(Instant.parse("2026-11-15T03:00:00.123456Z"));
    }

    @Test
    void anOrderDeliveredExactlyTheConfiguredDaysAgoIsDue() {
        order(OnlineOrderStatus.DELIVERED, Instant.parse("2026-11-08T03:00:00.123456Z"));

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.CONFIRMED);
    }

    @Test
    void anOrderDeliveredLessThanTheConfiguredDaysAgoIsSkipped() {
        order(OnlineOrderStatus.DELIVERED, Instant.parse("2026-11-08T03:00:00.123457Z"));

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void aLockedOrGoneOrderIsSkippedWithoutReading() {
        when(onlineOrders.lockDeliveredSkipLocked(ONLINE_ID)).thenReturn(Optional.empty());

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verify(onlineOrders, never()).findById(anyLong());
    }

    @Test
    void anOrderThatLeftDeliveredMeanwhileIsReCheckedUnderTheLock() {
        order(OnlineOrderStatus.COMPLETED, DELIVERED_8_DAYS_AGO);

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void theAutoConfirmDaysIsReadAtRunTimeOnEveryCall() {
        order(OnlineOrderStatus.DELIVERED, Instant.parse("2026-11-12T03:00:00Z"));
        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        when(config.getInt(ConfigKey.SHOP_AUTO_CONFIRM_DAYS)).thenReturn(3);

        assertThat(service.confirm(ONLINE_ID)).isEqualTo(Result.CONFIRMED);
    }

    @Test
    void confirmRefusesToRunInAReadOnlyTransaction() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThatThrownBy(() -> service.confirm(ONLINE_ID)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }

    @Test
    void findDueUsesTheCutoffFromTheConfiguredDays() {
        PageRequest page = PageRequest.of(0, 2);
        Instant cutoff = Instant.parse("2026-11-08T03:00:00.123456789Z");
        DueRow first = row(1, DELIVERED_8_DAYS_AGO);
        DueRow second = row(2, DELIVERED_8_DAYS_AGO);
        when(onlineOrders.findDeliveredBefore(cutoff, page)).thenReturn(List.of(first));
        when(onlineOrders.findDeliveredBeforeAfter(cutoff, DELIVERED_8_DAYS_AGO, 1L, page))
                .thenReturn(List.of(second));

        assertThat(service.findDue(NOW, null, 0, 2)).containsExactly(new DueOrder(1, DELIVERED_8_DAYS_AGO));
        assertThat(service.findDue(NOW, DELIVERED_8_DAYS_AGO, 1, 2))
                .containsExactly(new DueOrder(2, DELIVERED_8_DAYS_AGO));
    }
}
