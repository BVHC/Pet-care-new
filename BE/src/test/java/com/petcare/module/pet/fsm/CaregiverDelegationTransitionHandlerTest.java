package com.petcare.module.pet.fsm;

import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaregiverDelegationTransitionHandlerTest extends FsmTransitionTestBase<CaregiverStatus> {

    @Override
    protected StateMachineBase<CaregiverStatus> handler() {
        return new CaregiverDelegationTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "INVITED,ACTIVE",
            "INVITED,REJECTED",
            "INVITED,EXPIRED",
            "INVITED,REVOKED",   // spec D-04 — cạnh mới: chủ hủy lời mời đang treo
            "ACTIVE,REVOKED",
            "ACTIVE,EXPIRED"
    })
    void validTransitions(CaregiverStatus from, CaregiverStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "INVITED,INVITED",
            "ACTIVE,INVITED",
            "ACTIVE,ACTIVE",
            "ACTIVE,REJECTED",
            "REJECTED,INVITED",
            "REJECTED,ACTIVE",
            "REJECTED,REJECTED",
            "REJECTED,EXPIRED",
            "REJECTED,REVOKED",
            "EXPIRED,INVITED",
            "EXPIRED,ACTIVE",
            "EXPIRED,REJECTED",
            "EXPIRED,EXPIRED",
            "EXPIRED,REVOKED",
            "REVOKED,INVITED",
            "REVOKED,ACTIVE",
            "REVOKED,REJECTED",
            "REVOKED,EXPIRED",
            "REVOKED,REVOKED"
    })
    void invalidTransitions(CaregiverStatus from, CaregiverStatus to) {
        assertInvalidTransition(from, to);
    }
}
