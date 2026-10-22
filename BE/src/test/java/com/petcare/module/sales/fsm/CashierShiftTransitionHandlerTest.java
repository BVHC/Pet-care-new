package com.petcare.module.sales.fsm;

import static com.petcare.module.sales.entity.CashierShiftStatus.CLOSED;
import static com.petcare.module.sales.entity.CashierShiftStatus.OPEN;
import static com.petcare.module.sales.entity.CashierShiftStatus.RECONCILED;

import java.util.Map;
import java.util.Set;

import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "8. Ca thu ngân" (docs/03-state-machines.md): #1, #2 — → OPEN (ca thường, ca ngoài giờ),
 * #3, #4, #5 OPEN → CLOSED (lễ tân chốt, ST13 tự chốt hai loại ca), #6 CLOSED → RECONCILED. RECONCILED là cuối.
 */
class CashierShiftTransitionHandlerTest extends FsmTransitionTestBase<CashierShiftStatus> {

    @Override
    protected StateMachineBase<CashierShiftStatus> handler() {
        return new CashierShiftTransitionHandler();
    }

    @Override
    protected Class<CashierShiftStatus> stateType() {
        return CashierShiftStatus.class;
    }

    @Override
    protected Map<CashierShiftStatus, Set<CashierShiftStatus>> expectedTransitions() {
        return Map.of(OPEN, Set.of(CLOSED), CLOSED, Set.of(RECONCILED));
    }

    @Override
    protected Set<CashierShiftStatus> expectedInitialStates() {
        return Set.of(OPEN);
    }
}
