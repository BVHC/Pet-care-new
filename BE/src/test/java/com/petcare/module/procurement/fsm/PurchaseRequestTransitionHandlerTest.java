package com.petcare.module.procurement.fsm;

import com.petcare.platform.enums.PurchaseRequestStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** FSM-12 (docs/03-state-machines.md §12) — đủ 25 cặp (5x5) valid/invalid. */
class PurchaseRequestTransitionHandlerTest extends FsmTransitionTestBase<PurchaseRequestStatus> {

    @Override
    protected StateMachineBase<PurchaseRequestStatus> handler() {
        return new PurchaseRequestTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,SUBMITTED",
            "DRAFT,CANCELLED",
            "SUBMITTED,APPROVED",
            "SUBMITTED,REJECTED",
            "SUBMITTED,CANCELLED",
    })
    void validTransitions(PurchaseRequestStatus from, PurchaseRequestStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,DRAFT",
            "DRAFT,APPROVED",
            "DRAFT,REJECTED",
            "SUBMITTED,DRAFT",
            "SUBMITTED,SUBMITTED",
            "APPROVED,DRAFT",
            "APPROVED,SUBMITTED",
            "APPROVED,APPROVED",
            "APPROVED,REJECTED",
            "APPROVED,CANCELLED",
            "REJECTED,DRAFT",
            "REJECTED,SUBMITTED",
            "REJECTED,APPROVED",
            "REJECTED,REJECTED",
            "REJECTED,CANCELLED",
            "CANCELLED,DRAFT",
            "CANCELLED,SUBMITTED",
            "CANCELLED,APPROVED",
            "CANCELLED,REJECTED",
            "CANCELLED,CANCELLED",
    })
    void invalidTransitions(PurchaseRequestStatus from, PurchaseRequestStatus to) {
        assertInvalidTransition(from, to);
    }
}
