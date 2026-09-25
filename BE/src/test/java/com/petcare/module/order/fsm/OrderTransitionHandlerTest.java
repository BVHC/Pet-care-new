package com.petcare.module.order.fsm;

import com.petcare.platform.enums.OrderStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FSM-5 (docs/03-state-machines.md §5) — CHỈ slice có call site thật trong phạm vi task Module 14
 * hiện tại (xem javadoc {@link OrderTransitionHandler}): đủ 64 cặp (8x8, gồm cả self-pair) — 1
 * valid, 63 invalid cho {@code Map.of(PENDING_PAYMENT, Set.of(CANCELLED))}. Không phải toàn bộ
 * FSM-5 8 state đầy đủ — các cạnh khác chưa có call site (ConfirmOrder/ProcessOrder/... để task sau).
 */
class OrderTransitionHandlerTest extends FsmTransitionTestBase<OrderStatus> {

    @Override
    protected StateMachineBase<OrderStatus> handler() {
        return new OrderTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT,CANCELLED",
    })
    void validTransitions(OrderStatus from, OrderStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT,PENDING_PAYMENT",
            "PENDING_PAYMENT,PAID",
            "PENDING_PAYMENT,CONFIRMED",
            "PENDING_PAYMENT,PROCESSING",
            "PENDING_PAYMENT,READY",
            "PENDING_PAYMENT,DELIVERED",
            "PENDING_PAYMENT,REFUNDED",
            "PAID,PENDING_PAYMENT",
            "PAID,PAID",
            "PAID,CONFIRMED",
            "PAID,PROCESSING",
            "PAID,READY",
            "PAID,DELIVERED",
            "PAID,CANCELLED",
            "PAID,REFUNDED",
            "CONFIRMED,PENDING_PAYMENT",
            "CONFIRMED,PAID",
            "CONFIRMED,CONFIRMED",
            "CONFIRMED,PROCESSING",
            "CONFIRMED,READY",
            "CONFIRMED,DELIVERED",
            "CONFIRMED,CANCELLED",
            "CONFIRMED,REFUNDED",
            "PROCESSING,PENDING_PAYMENT",
            "PROCESSING,PAID",
            "PROCESSING,CONFIRMED",
            "PROCESSING,PROCESSING",
            "PROCESSING,READY",
            "PROCESSING,DELIVERED",
            "PROCESSING,CANCELLED",
            "PROCESSING,REFUNDED",
            "READY,PENDING_PAYMENT",
            "READY,PAID",
            "READY,CONFIRMED",
            "READY,PROCESSING",
            "READY,READY",
            "READY,DELIVERED",
            "READY,CANCELLED",
            "READY,REFUNDED",
            "DELIVERED,PENDING_PAYMENT",
            "DELIVERED,PAID",
            "DELIVERED,CONFIRMED",
            "DELIVERED,PROCESSING",
            "DELIVERED,READY",
            "DELIVERED,DELIVERED",
            "DELIVERED,CANCELLED",
            "DELIVERED,REFUNDED",
            "CANCELLED,PENDING_PAYMENT",
            "CANCELLED,PAID",
            "CANCELLED,CONFIRMED",
            "CANCELLED,PROCESSING",
            "CANCELLED,READY",
            "CANCELLED,DELIVERED",
            "CANCELLED,CANCELLED",
            "CANCELLED,REFUNDED",
            "REFUNDED,PENDING_PAYMENT",
            "REFUNDED,PAID",
            "REFUNDED,CONFIRMED",
            "REFUNDED,PROCESSING",
            "REFUNDED,READY",
            "REFUNDED,DELIVERED",
            "REFUNDED,CANCELLED",
            "REFUNDED,REFUNDED",
    })
    void invalidTransitions(OrderStatus from, OrderStatus to) {
        assertInvalidTransition(from, to);
    }
}
