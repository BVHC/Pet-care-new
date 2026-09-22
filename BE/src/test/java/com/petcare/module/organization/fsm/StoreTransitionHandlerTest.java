package com.petcare.module.organization.fsm;

import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Đủ toàn bộ 25 cặp (from,to) của FSM 2 (5 state) theo mermaid docs/03-state-machines.md §2 —
 * 7 hợp lệ, 18 không hợp lệ. Bao gồm cả cạnh Suspend/Deactivate/Archive dù chưa có command dùng
 * tới ở lần triển khai này (chỉ ActivateStore) — StoreTransitionHandler khai báo đầy đủ map.
 */
class StoreTransitionHandlerTest extends FsmTransitionTestBase<StoreStatus> {

    @Override
    protected StateMachineBase<StoreStatus> handler() {
        return new StoreTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,ACTIVE",
            "ACTIVE,SUSPENDED",
            "ACTIVE,DEACTIVATED",
            "SUSPENDED,ACTIVE",
            "SUSPENDED,ARCHIVED",
            "DEACTIVATED,ACTIVE",
            "DEACTIVATED,ARCHIVED"
    })
    void validTransitions(StoreStatus from, StoreStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT,DRAFT",
            "DRAFT,SUSPENDED",
            "DRAFT,DEACTIVATED",
            "DRAFT,ARCHIVED",
            "ACTIVE,DRAFT",
            "ACTIVE,ACTIVE",
            "ACTIVE,ARCHIVED",
            "SUSPENDED,DRAFT",
            "SUSPENDED,SUSPENDED",
            "SUSPENDED,DEACTIVATED",
            "DEACTIVATED,DRAFT",
            "DEACTIVATED,SUSPENDED",
            "DEACTIVATED,DEACTIVATED",
            "ARCHIVED,DRAFT",
            "ARCHIVED,ACTIVE",
            "ARCHIVED,SUSPENDED",
            "ARCHIVED,DEACTIVATED",
            "ARCHIVED,ARCHIVED"
    })
    void invalidTransitions(StoreStatus from, StoreStatus to) {
        assertInvalidTransition(from, to);
    }
}
