package com.petcare.module.inpatient.fsm;

import static com.petcare.module.inpatient.entity.AdmissionStatus.ADMITTED;
import static com.petcare.module.inpatient.entity.AdmissionStatus.DISCHARGED;

import java.util.Map;
import java.util.Set;

import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của "Máy trạng thái #24 — Nhập viện" (docs/09-tang3-be1-spec.md §C): #1 — → ADMITTED; #2 ADMITTED →
 * DISCHARGED. DISCHARGED là FINAL; bảng này <b>không</b> có cạnh X → X.
 */
class AdmissionTransitionHandlerTest extends FsmTransitionTestBase<AdmissionStatus> {

    @Override
    protected StateMachineBase<AdmissionStatus> handler() {
        return new AdmissionTransitionHandler();
    }

    @Override
    protected Class<AdmissionStatus> stateType() {
        return AdmissionStatus.class;
    }

    @Override
    protected Map<AdmissionStatus, Set<AdmissionStatus>> expectedTransitions() {
        return Map.of(ADMITTED, Set.of(DISCHARGED));        // #2
    }

    @Override
    protected Set<AdmissionStatus> expectedInitialStates() {
        return Set.of(ADMITTED);                            // #1
    }
}
