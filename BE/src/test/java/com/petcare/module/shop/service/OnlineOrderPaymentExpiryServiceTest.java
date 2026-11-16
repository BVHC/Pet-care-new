package com.petcare.module.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderRef;
import com.petcare.module.payment.api.PaymentTransactionApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineCancelType;
import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.entity.OnlineOrder;
import com.petcare.module.shop.fsm.OnlineOrderTransitionHandler;
import com.petcare.module.shop.repository.OnlineOrderRepository;
import com.petcare.module.shop.repository.OnlineOrderRepository.DueRow;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderPaymentExpiryService.Result;

/**
 * ST10 (docs/adr/0146): khóa SKIP LOCKED trước, đọc lại và kiểm lại dưới khóa, hệ quả đúng thứ tự khóa của ADR-0145
 * ({@code orders} → {@code payment_transactions} → {@code stock_reservations}), nhánh bỏ qua không ghi gì.
 */
class OnlineOrderPaymentExpiryServiceTest {

    private static final long ONLINE_ID = 600L;
    private static final long ORDER_ID = 500L;
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00.123456789Z");
    private static final Instant DUE = Instant.parse("2026-11-15T02:00:00Z");

    private final OnlineOrderRepository onlineOrders = mock(OnlineOrderRepository.class);
    private final OnlineSalesOrderApi sales = mock(OnlineSalesOrderApi.class);
    private final PaymentTransactionApi payments = mock(PaymentTransactionApi.class);
    private final StockApi stock = mock(StockApi.class);
    private final OnlineOrderNotices notices = mock(OnlineOrderNotices.class);
    private OnlineOrderPaymentExpiryService service;

    @BeforeEach
    void setUp() {
        when(onlineOrders.lockPendingPaymentSkipLocked(ONLINE_ID)).thenReturn(Optional.of(ONLINE_ID));
        when(onlineOrders.saveAndFlush(any(OnlineOrder.class))).thenAnswer(call -> call.getArgument(0));
        service = new OnlineOrderPaymentExpiryService(onlineOrders, sales, payments, stock, notices,
                new OnlineOrderTransitionHandler(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OnlineOrder order(OnlineOrderStatus status, Instant due) {
        OnlineOrder order = OnlineOrder.place("DO-2611-00001", ORDER_ID, 9L, 3L, "An", "0901", "12 Lê Lợi", 30_000, due);
        ReflectionTestUtils.setField(order, "id", ONLINE_ID);
        ReflectionTestUtils.setField(order, "status", status);
        when(onlineOrders.findById(ONLINE_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static DueRow row(long id, Instant due) {
        DueRow row = mock(DueRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getDueAt()).thenReturn(due);
        return row;
    }

    @Test
    void expiresADueOrderLockingFirstThenCancellingOrderTransactionReservationAndNotifying() {
        OnlineOrder order = order(OnlineOrderStatus.PENDING_PAYMENT, DUE);

        Result result = service.expire(ONLINE_ID);

        assertThat(result).isEqualTo(Result.EXPIRED);
        InOrder inOrder = inOrder(onlineOrders, sales, payments, stock, notices);
        inOrder.verify(onlineOrders).lockPendingPaymentSkipLocked(ONLINE_ID);
        inOrder.verify(onlineOrders).findById(ONLINE_ID);
        inOrder.verify(sales).cancel(ORDER_ID, OnlineCancelType.EXPIRED, null, "Quá hạn thanh toán");
        inOrder.verify(payments).cancelPendingForOnlineOrder(ONLINE_ID);
        inOrder.verify(stock).release(new OrderRef(ONLINE_ID, ORDER_ID));
        inOrder.verify(onlineOrders).saveAndFlush(order);
        inOrder.verify(notices).cancelled(order, "Quá hạn thanh toán");
        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).as("hệ thống hủy").isNull();
        assertThat(order.getCancelReason()).isEqualTo("Quá hạn thanh toán");
        assertThat(order.getCancelledAt()).as("giờ từ Clock sau khóa, cắt về micro giây")
                .isEqualTo(Instant.parse("2026-11-15T03:00:00.123456Z"));
    }

    @Test
    void anOrderDueExactlyNowIsExpired() {
        order(OnlineOrderStatus.PENDING_PAYMENT, Instant.parse("2026-11-15T03:00:00.123456Z"));

        assertThat(service.expire(ONLINE_ID)).isEqualTo(Result.EXPIRED);
    }

    @Test
    void aLockedOrGoneOrderIsSkippedWithoutReadingOrWriting() {
        when(onlineOrders.lockPendingPaymentSkipLocked(ONLINE_ID)).thenReturn(Optional.empty());

        assertThat(service.expire(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verify(onlineOrders, never()).findById(anyLong());
        verifyNoInteractions(sales, payments, stock, notices);
    }

    @Test
    void anOrderThatLeftPendingPaymentMeanwhileIsReCheckedUnderTheLockAndSkipped() {
        order(OnlineOrderStatus.PAID, DUE);

        assertThat(service.expire(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verifyNoInteractions(sales, payments, stock, notices);
        verify(onlineOrders, never()).saveAndFlush(any());
    }

    @Test
    void anOrderNotYetDueIsSkipped() {
        order(OnlineOrderStatus.PENDING_PAYMENT, Instant.parse("2026-11-15T03:00:01Z"));

        assertThat(service.expire(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        verifyNoInteractions(sales, payments, stock, notices);
    }

    @Test
    void anOrderWithASucceededTransactionIsSkippedNotCancelled() {
        OnlineOrder order = order(OnlineOrderStatus.PENDING_PAYMENT, DUE);
        when(payments.hasSucceededForOnlineOrder(ONLINE_ID)).thenReturn(true);

        assertThat(service.expire(ONLINE_ID)).isEqualTo(Result.SKIPPED);

        assertThat(order.getStatus()).isEqualTo(OnlineOrderStatus.PENDING_PAYMENT);
        verifyNoInteractions(sales, stock, notices);
        verify(payments, never()).cancelPendingForOnlineOrder(anyLong());
    }

    @Test
    void aFailureInAnyStepPropagatesSoTheWholeOrderRollsBack() {
        order(OnlineOrderStatus.PENDING_PAYMENT, DUE);
        doThrow(new IllegalStateException("kho")).when(stock).release(any());

        assertThatThrownBy(() -> service.expire(ONLINE_ID)).isInstanceOf(IllegalStateException.class);

        verify(onlineOrders, never()).saveAndFlush(any());
        verifyNoInteractions(notices);
    }

    @Test
    void expireRefusesToRunInAReadOnlyTransaction() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThatThrownBy(() -> service.expire(ONLINE_ID)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
        verifyNoInteractions(sales, payments, stock, notices);
    }

    @Test
    void findDueUsesTheFirstBatchQueryThenTheKeysetQuery() {
        PageRequest page = PageRequest.of(0, 2);
        DueRow first = row(1, DUE);
        DueRow second = row(2, DUE);
        when(onlineOrders.findExpiredPayment(NOW, page)).thenReturn(List.of(first));
        when(onlineOrders.findExpiredPaymentAfter(NOW, DUE, 1L, page)).thenReturn(List.of(second));

        assertThat(service.findDue(NOW, null, 0, 2)).containsExactly(new DueOrder(1, DUE));
        assertThat(service.findDue(NOW, DUE, 1, 2)).containsExactly(new DueOrder(2, DUE));
    }
}
