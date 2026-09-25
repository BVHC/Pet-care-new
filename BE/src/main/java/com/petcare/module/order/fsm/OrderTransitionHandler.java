package com.petcare.module.order.fsm;

import com.petcare.platform.enums.OrderStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.OrderStatus.CANCELLED;
import static com.petcare.platform.enums.OrderStatus.CONFIRMED;
import static com.petcare.platform.enums.OrderStatus.DELIVERED;
import static com.petcare.platform.enums.OrderStatus.PAID;
import static com.petcare.platform.enums.OrderStatus.PENDING_PAYMENT;
import static com.petcare.platform.enums.OrderStatus.PROCESSING;
import static com.petcare.platform.enums.OrderStatus.READY;

/**
 * FSM-5 (docs/03-state-machines.md §5) — slice có call site thật cho Module 14: CreateOrder/
 * CheckoutOrder/ViewOrder/CancelOrder + ConfirmOrder/ProcessOrder/PrepareProductOrder/
 * CompleteStoreOrder (RULE-14-03/05/06). {@code CancelOrderWithRefund} (PAID/CONFIRMED/PROCESSING/
 * READY -> CANCELLED) và event {@code RefundCompleted} (DELIVERED -> REFUNDED) vẫn KHÔNG có cạnh ở
 * đây — để dành task sau, phụ thuộc Refund M17 chưa tồn tại (KHÔNG tự thêm cạnh suy diễn từ mermaid
 * đầy đủ khi chưa có command thật gọi tới, theo đúng tiền lệ Module 13
 * {@code PurchaseOrderTransitionHandler}).
 */
@Component
public class OrderTransitionHandler extends StateMachineBase<OrderStatus> {

    @Override
    public Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        return Map.of(
                PENDING_PAYMENT, Set.of(CANCELLED),
                PAID, Set.of(CONFIRMED, DELIVERED),
                CONFIRMED, Set.of(PROCESSING),
                PROCESSING, Set.of(READY),
                READY, Set.of(DELIVERED)
        );
    }
}
