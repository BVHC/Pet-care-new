package com.petcare.module.auth.fsm;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Đủ toàn bộ 16 cặp (from,to) của FSM 1 (4 state) theo mermaid
 * docs/03-state-machines.md §1 — 6 hợp lệ, 10 không hợp lệ.
 */
class AccountTransitionHandlerTest extends FsmTransitionTestBase<AccountStatus> {

    @Override
    protected StateMachineBase<AccountStatus> handler() {
        return new AccountTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_VERIFICATION,ACTIVE",
            "ACTIVE,LOCKED",
            "ACTIVE,DEACTIVATED",
            "LOCKED,ACTIVE",
            "LOCKED,DEACTIVATED",
            "DEACTIVATED,ACTIVE"
    })
    void validTransitions(AccountStatus from, AccountStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_VERIFICATION,PENDING_VERIFICATION",
            "PENDING_VERIFICATION,LOCKED",
            "PENDING_VERIFICATION,DEACTIVATED",
            "ACTIVE,PENDING_VERIFICATION",
            "ACTIVE,ACTIVE",
            "LOCKED,PENDING_VERIFICATION",
            "LOCKED,LOCKED",
            "DEACTIVATED,PENDING_VERIFICATION",
            "DEACTIVATED,LOCKED",
            "DEACTIVATED,DEACTIVATED"
    })
    void invalidTransitions(AccountStatus from, AccountStatus to) {
        assertInvalidTransition(from, to);
    }
}
