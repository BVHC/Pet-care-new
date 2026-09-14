package com.petcare.module.auth.fsm;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.AccountStatus.ACTIVE;
import static com.petcare.platform.enums.AccountStatus.DEACTIVATED;
import static com.petcare.platform.enums.AccountStatus.LOCKED;
import static com.petcare.platform.enums.AccountStatus.PENDING_VERIFICATION;

/**
 * FSM 1 (Account) — docs/03-state-machines.md §1. Khai báo đầy đủ transition
 * map dù phạm vi lần này (RegisterAccount+OTP) chỉ dùng
 * PENDING_VERIFICATION->ACTIVE — các cạnh còn lại (Lock/Unlock/Deactivate/
 * Reactivate) dành cho module Login/IAM triển khai sau, tái dùng đúng class
 * này (docs/convention/backend/05-fsm-pattern.md).
 */
@Component
public class AccountTransitionHandler extends StateMachineBase<AccountStatus> {

    @Override
    public Map<AccountStatus, Set<AccountStatus>> allowedTransitions() {
        return Map.of(
                PENDING_VERIFICATION, Set.of(ACTIVE),
                ACTIVE, Set.of(LOCKED, DEACTIVATED),
                LOCKED, Set.of(ACTIVE, DEACTIVATED),
                DEACTIVATED, Set.of(ACTIVE)
        );
    }
}
