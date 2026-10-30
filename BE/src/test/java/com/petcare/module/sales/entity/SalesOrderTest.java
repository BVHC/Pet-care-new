package com.petcare.module.sales.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.exception.OrderAmountOverflowException;
import com.petcare.platform.exception.ErrorCode;

class SalesOrderTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    private static SalesOrder order() {
        return SalesOrder.openForVisit("DH-2610-00001", 1L, 2L, 3L, 4L);
    }

    private static OrderLine service(int quantity, long price) {
        return OrderLine.of(OrderLineType.SERVICE, 10L, null, "Kham", quantity, price, true, 4L, 4L);
    }

    private static OrderLine goods(int quantity, long price) {
        return OrderLine.of(OrderLineType.GOODS, null, 20L, "Hat", quantity, price, false, 5L, 5L);
    }

    @Test
    void newVisitOrderIsOpenAndEmpty() {
        SalesOrder order = order();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(order.getSource()).isEqualTo(OrderSource.VISIT);
        assertThat(order.getVisitId()).isEqualTo(3L);
        assertThat(order.getBoardingBookingId()).isNull();
        assertThat(order.getTotalAmount()).isZero();
        assertThat(order.getLines()).isEmpty();
    }

    @Test
    void totalIsRecomputedFromLinesOnAddAndRemove() {
        SalesOrder order = order();
        OrderLine first = service(1, 150_000);
        OrderLine second = goods(3, 40_000);

        order.addLine(first);
        order.addLine(second);
        assertThat(order.getTotalAmount()).isEqualTo(270_000);

        order.removeLine(second);
        assertThat(order.getTotalAmount()).isEqualTo(150_000);
    }

    @Test
    void changingQuantityKeepsTheSnapshotPrice() {
        OrderLine line = goods(5, 40_000);

        line.changeQuantity(2);

        assertThat(line.getUnitPrice()).isEqualTo(40_000);
        assertThat(line.getLineTotal()).isEqualTo(80_000);
    }

    @Test
    void replacingServiceSnapshotsNameAndPriceAgain() {
        OrderLine line = service(1, 150_000);

        line.replaceService(11L, "Tiem phong", 220_000);

        assertThat(line.getServiceId()).isEqualTo(11L);
        assertThat(line.getDescription()).isEqualTo("Tiem phong");
        assertThat(line.getUnitPrice()).isEqualTo(220_000);
        assertThat(line.getLineTotal()).isEqualTo(220_000);
    }

    @Test
    void lineTotalOverflowIsRefused() {
        assertThatThrownBy(() -> goods(Integer.MAX_VALUE, Long.MAX_VALUE))
                .isInstanceOfSatisfying(OrderAmountOverflowException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void orderTotalOverflowIsRefusedAndKeepsTheOldTotal() {
        SalesOrder order = order();
        order.addLine(goods(1, Long.MAX_VALUE - 5));

        assertThatThrownBy(() -> order.addLine(goods(1, 10)))
                .isInstanceOf(OrderAmountOverflowException.class);
        assertThat(order.getTotalAmount()).isEqualTo(Long.MAX_VALUE - 5);
    }

    @Test
    void markPendingSetsPendingAt() {
        SalesOrder order = order();

        order.markPending(NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPendingAt()).isEqualTo(NOW);
    }

    @Test
    void cancelRecordsTypeActorAndTime() {
        SalesOrder order = order();

        order.cancel(OrderCancelType.VISIT_CANCELLED, 9L, null, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.VISIT_CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(9L);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(order.getCancelReason()).isNull();
    }

    @Test
    void transferOwnerChangesOnlyTheOwner() {
        OrderLine line = service(1, 100);

        line.transferOwnerTo(77L);

        assertThat(line.getOwnerAccountId()).isEqualTo(77L);
        assertThat(line.getAddedBy()).isEqualTo(4L);
    }

    @Test
    void markPaidSetsStatusPaymentAndTime() {
        SalesOrder order = order();
        order.markPending(NOW);

        order.markPaid(77L, NOW.plusSeconds(60));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaymentId()).isEqualTo(77L);
        assertThat(order.getPaidAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void boardingOrderStartsPendingWithBookingSourceAndNoVisit() {
        SalesOrder order = SalesOrder.openForBoarding("DH-2610-00009", 1L, 2L, 88L, 4L, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSource()).isEqualTo(OrderSource.BOARDING);
        assertThat(order.getBoardingBookingId()).isEqualTo(88L);
        assertThat(order.getVisitId()).isNull();
        assertThat(order.getPendingAt()).isEqualTo(NOW);
    }

    @Test
    void repriceBoardingChangesQuantityAndTotalKeepsUnitPrice() {
        SalesOrder order = SalesOrder.openForBoarding("DH-2610-00009", 1L, 2L, 88L, 4L, NOW);
        OrderLine line = OrderLine.of(OrderLineType.BOARDING, 5L, null, "Luu tru", 2, 150_000L, true, 4L, 4L);
        order.addLine(line);

        line.repriceBoarding(4);
        order.recalculateTotal();

        assertThat(line.getQuantity()).isEqualTo(4);
        assertThat(line.getUnitPrice()).isEqualTo(150_000L);
        assertThat(line.getLineTotal()).isEqualTo(600_000L);
        assertThat(order.getTotalAmount()).isEqualTo(600_000L);
    }

    @Test
    void repriceOfNonBoardingLineThrows() {
        assertThatThrownBy(() -> service(1, 100L).repriceBoarding(2)).isInstanceOf(IllegalStateException.class);
    }
}
