package com.petcare.module.order.fsm;

import com.petcare.platform.enums.OrderStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.OrderStatus.CANCELLED;
import static com.petcare.platform.enums.OrderStatus.PENDING_PAYMENT;

/**
 * FSM-5 (docs/03-state-machines.md §5) — CHỈ slice có call site thật trong phạm vi task Module 14
 * hiện tại (`CreateOrder`/`CheckoutOrder`/`ViewOrder`/`CancelOrder`): cạnh duy nhất là
 * {@code PENDING_PAYMENT -> CANCELLED}, dùng chung bởi {@code CancelOrder} (customer/receptionist)
 * và {@code ProcessOrderTimeout} (job nền). FSM-5 đầy đủ có 8 state/nhiều cạnh khác
 * ({@code PAID->CONFIRMED}, {@code CONFIRMED->PROCESSING}, v.v.) nhưng chưa có call site
 * (`ConfirmOrder`/`ProcessOrder`/`PrepareProductOrder`/`CompleteStoreOrder`/`CancelOrderWithRefund`
 * để dành task sau, phụ thuộc Payment M16/Refund M17) — theo đúng tiền lệ Module 13
 * ({@code PurchaseOrderTransitionHandler} không được tạo vì chưa có call site cho cạnh khác),
 * KHÔNG tự thêm cạnh suy diễn từ mermaid đầy đủ khi chưa có command thật gọi tới.
 */
@Component
public class OrderTransitionHandler extends StateMachineBase<OrderStatus> {

    @Override
    public Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        return Map.of(
                PENDING_PAYMENT, Set.of(CANCELLED)
        );
    }
}
