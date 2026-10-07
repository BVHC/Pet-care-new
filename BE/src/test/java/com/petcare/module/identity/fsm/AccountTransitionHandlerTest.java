package com.petcare.module.identity.fsm;

import static com.petcare.module.identity.entity.AccountStatus.ACTIVE;
import static com.petcare.module.identity.entity.AccountStatus.DISABLED;
import static com.petcare.module.identity.entity.AccountStatus.PENDING;

import java.util.Map;
import java.util.Set;

import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "1. Tài khoản" (docs/03-state-machines.md L42–51), phần {@code status}:
 * #3 là xóa cứng, #7/#8 là cờ {@code is_locked} — không phải cạnh.
 */
class AccountTransitionHandlerTest extends FsmTransitionTestBase<AccountStatus> {

    @Override
    protected StateMachineBase<AccountStatus> handler() {
        return new AccountTransitionHandler();
    }

    @Override
    protected Class<AccountStatus> stateType() {
        return AccountStatus.class;
    }

    @Override
    protected Map<AccountStatus, Set<AccountStatus>> expectedTransitions() {
        return Map.of(
                PENDING, Set.of(ACTIVE),        // #2
                ACTIVE, Set.of(DISABLED),       // #5
                DISABLED, Set.of(ACTIVE));      // #6
    }

    @Override
    protected Set<AccountStatus> expectedInitialStates() {
        return Set.of(PENDING, ACTIVE);         // #1, #4
    }
}
