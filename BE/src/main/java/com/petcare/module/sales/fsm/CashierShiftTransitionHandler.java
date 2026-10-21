package com.petcare.module.sales.fsm;

import static com.petcare.module.sales.entity.CashierShiftStatus.CLOSED;
import static com.petcare.module.sales.entity.CashierShiftStatus.OPEN;
import static com.petcare.module.sales.entity.CashierShiftStatus.RECONCILED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "8. Ca thu ngân" của docs/03-state-machines.md: #1, #2 — → OPEN, #3, #4, #5 OPEN → CLOSED (lễ tân chốt, ST13 tự
 * chốt ca thường / ngoài giờ), #6 CLOSED → RECONCILED. Điều kiện mở ca, giờ tự chốt là guard của service.
 */
@Component
public class CashierShiftTransitionHandler extends StateMachineBase<CashierShiftStatus> {

    @Override
    public Map<CashierShiftStatus, Set<CashierShiftStatus>> allowedTransitions() {
        return Map.of(
                OPEN, Set.of(CLOSED),                      // #3 chốt; #4, #5 tự chốt
                CLOSED, Set.of(RECONCILED));               // #6 đối soát
    }

    @Override
    public Set<CashierShiftStatus> initialStates() {
        return Set.of(OPEN);                               // #1, #2
    }

    @Override
    protected String fsmName() {
        return "ca thu ngân";
    }
}
