package com.petcare.module.boarding.fsm;

import static com.petcare.module.boarding.entity.KennelStatus.AVAILABLE;
import static com.petcare.module.boarding.entity.KennelStatus.MAINTENANCE;
import static com.petcare.module.boarding.entity.KennelStatus.OCCUPIED;

import java.util.Map;
import java.util.Set;

import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "7. Chuồng" (docs/03-state-machines.md): #1 — → AVAILABLE, #2 AVAILABLE → OCCUPIED,
 * #3 OCCUPIED → AVAILABLE, #4 AVAILABLE → MAINTENANCE, #5 MAINTENANCE → AVAILABLE.
 */
class KennelTransitionHandlerTest extends FsmTransitionTestBase<KennelStatus> {

    @Override
    protected StateMachineBase<KennelStatus> handler() {
        return new KennelTransitionHandler();
    }

    @Override
    protected Class<KennelStatus> stateType() {
        return KennelStatus.class;
    }

    @Override
    protected Map<KennelStatus, Set<KennelStatus>> expectedTransitions() {
        return Map.of(
                AVAILABLE, Set.of(OCCUPIED, MAINTENANCE),
                OCCUPIED, Set.of(AVAILABLE),
                MAINTENANCE, Set.of(AVAILABLE));
    }

    @Override
    protected Set<KennelStatus> expectedInitialStates() {
        return Set.of(AVAILABLE);
    }
}
