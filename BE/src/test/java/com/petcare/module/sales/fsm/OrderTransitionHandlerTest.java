package com.petcare.module.sales.fsm;

import static com.petcare.module.sales.entity.OrderStatus.CANCELLED;
import static com.petcare.module.sales.entity.OrderStatus.OPEN;
import static com.petcare.module.sales.entity.OrderStatus.PAID;
import static com.petcare.module.sales.entity.OrderStatus.PENDING;

import java.util.Map;
import java.util.Set;

import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "5. Order" (docs/03-state-machines.md): #1, #2 — → OPEN, #3 — → PENDING (BOARDING),
 * #4, #5 OPEN → PENDING, #6, #7 OPEN → CANCELLED, #8 PENDING → PAID, #9, #10 PENDING → CANCELLED.
 * PAID và CANCELLED là trạng thái cuối.
 */
class OrderTransitionHandlerTest extends FsmTransitionTestBase<OrderStatus> {

    @Override
    protected StateMachineBase<OrderStatus> handler() {
        return new OrderTransitionHandler();
    }

    @Override
    protected Class<OrderStatus> stateType() {
        return OrderStatus.class;
    }

    @Override
    protected Map<OrderStatus, Set<OrderStatus>> expectedTransitions() {
        return Map.of(OPEN, Set.of(PENDING, CANCELLED), PENDING, Set.of(PAID, CANCELLED));
    }

    @Override
    protected Set<OrderStatus> expectedInitialStates() {
        return Set.of(OPEN, PENDING);
    }
}
