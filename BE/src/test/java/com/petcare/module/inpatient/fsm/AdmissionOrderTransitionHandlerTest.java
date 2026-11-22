package com.petcare.module.inpatient.fsm;

import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.CANCELLED;
import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.DONE;
import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.ORDERED;

import java.util.Map;
import java.util.Set;

import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của "Máy trạng thái #25 — Y lệnh nội trú" (docs/09-tang3-be1-spec.md §C): #1 — → ORDERED; #2 ORDERED
 * → DONE; #3 ORDERED → CANCELLED. DONE và CANCELLED là FINAL; bảng này <b>không</b> có cạnh X → X.
 */
class AdmissionOrderTransitionHandlerTest extends FsmTransitionTestBase<AdmissionOrderStatus> {

    @Override
    protected StateMachineBase<AdmissionOrderStatus> handler() {
        return new AdmissionOrderTransitionHandler();
    }

    @Override
    protected Class<AdmissionOrderStatus> stateType() {
        return AdmissionOrderStatus.class;
    }

    @Override
    protected Map<AdmissionOrderStatus, Set<AdmissionOrderStatus>> expectedTransitions() {
        return Map.of(ORDERED, Set.of(DONE, CANCELLED));    // #2 | #3
    }

    @Override
    protected Set<AdmissionOrderStatus> expectedInitialStates() {
        return Set.of(ORDERED);                             // #1
    }
}
