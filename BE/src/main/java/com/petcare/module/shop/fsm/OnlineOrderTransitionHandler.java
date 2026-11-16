package com.petcare.module.shop.fsm;

import static com.petcare.module.shop.api.OnlineOrderStatus.CANCELLED;
import static com.petcare.module.shop.api.OnlineOrderStatus.COMPLETED;
import static com.petcare.module.shop.api.OnlineOrderStatus.DELIVERED;
import static com.petcare.module.shop.api.OnlineOrderStatus.DELIVERY_FAILED;
import static com.petcare.module.shop.api.OnlineOrderStatus.PAID;
import static com.petcare.module.shop.api.OnlineOrderStatus.PENDING_PAYMENT;
import static com.petcare.module.shop.api.OnlineOrderStatus.PROCESSING;
import static com.petcare.module.shop.api.OnlineOrderStatus.SHIPPED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * FSM #14 (docs/08-tang3-be2-spec.md §B): #1 — → PENDING_PAYMENT; #2 PENDING_PAYMENT → PAID; #3, #4 PENDING_PAYMENT →
 * CANCELLED; #5 PAID → PROCESSING; #6 PROCESSING → SHIPPED; #7 SHIPPED → DELIVERED; #8 SHIPPED → DELIVERY_FAILED;
 * #9 DELIVERY_FAILED → SHIPPED; #10 DELIVERY_FAILED → CANCELLED; #11, #12 DELIVERED → COMPLETED; #13 PAID / PROCESSING →
 * CANCELLED. Guard theo actor và điều kiện (chưa SHIPPED, chưa có giao dịch SUCCEEDED…) là việc của service, chạy
 * trước {@code validateTransition}. COMPLETED và CANCELLED là trạng thái cuối.
 */
@Component
public class OnlineOrderTransitionHandler extends StateMachineBase<OnlineOrderStatus> {

    @Override
    public Map<OnlineOrderStatus, Set<OnlineOrderStatus>> allowedTransitions() {
        return Map.of(
                PENDING_PAYMENT, Set.of(PAID, CANCELLED),            // #2; #3, #4
                PAID, Set.of(PROCESSING, CANCELLED),                 // #5; #13
                PROCESSING, Set.of(SHIPPED, CANCELLED),              // #6; #13
                SHIPPED, Set.of(DELIVERED, DELIVERY_FAILED),         // #7; #8
                DELIVERY_FAILED, Set.of(SHIPPED, CANCELLED),         // #9; #10
                DELIVERED, Set.of(COMPLETED));                       // #11, #12
    }

    @Override
    public Set<OnlineOrderStatus> initialStates() {
        return Set.of(PENDING_PAYMENT);                              // #1
    }

    @Override
    protected String fsmName() {
        return "đơn hàng online";
    }
}
