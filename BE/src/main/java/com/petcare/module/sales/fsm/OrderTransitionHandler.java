package com.petcare.module.sales.fsm;

import static com.petcare.module.sales.entity.OrderStatus.CANCELLED;
import static com.petcare.module.sales.entity.OrderStatus.OPEN;
import static com.petcare.module.sales.entity.OrderStatus.PAID;
import static com.petcare.module.sales.entity.OrderStatus.PENDING;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "5. Order" của docs/03-state-machines.md: #1, #2 — → OPEN (VISIT, RETAIL), #3 — → PENDING (BOARDING),
 * #4, #5 OPEN → PENDING, #6, #7 OPEN → CANCELLED, #8 PENDING → PAID, #9, #10 PENDING → CANCELLED. Điều kiện theo
 * nguồn (Order#5 có ≥ 1 dòng, #9 chỉ BOARDING…) là guard của service, chạy trước {@code validateTransition}.
 */
@Component
public class OrderTransitionHandler extends StateMachineBase<OrderStatus> {

    @Override
    public Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        return Map.of(
                OPEN, Set.of(PENDING, CANCELLED),          // #4, #5 chốt; #6, #7 hủy
                PENDING, Set.of(PAID, CANCELLED));         // #8 thu tiền; #9, #10 hủy
    }

    @Override
    public Set<OrderStatus> initialStates() {
        return Set.of(OPEN, PENDING);                      // #1, #2 OPEN; #3 PENDING (chỉ nguồn BOARDING)
    }

    @Override
    protected String fsmName() {
        return "đơn hàng";
    }
}
