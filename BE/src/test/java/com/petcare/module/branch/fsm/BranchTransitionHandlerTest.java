package com.petcare.module.branch.fsm;

import static com.petcare.module.branch.entity.BranchStatus.ACTIVE;
import static com.petcare.module.branch.entity.BranchStatus.DRAFT;

import java.util.Map;
import java.util.Set;

import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "2. Chi nhánh" (docs/03-state-machines.md L53–66): #1 — → DRAFT, #2 DRAFT → ACTIVE.
 * Tạm ngừng / đóng cửa (UC13) là tầng 3 nên không có cạnh nào ra khỏi ACTIVE.
 */
class BranchTransitionHandlerTest extends FsmTransitionTestBase<BranchStatus> {

    @Override
    protected StateMachineBase<BranchStatus> handler() {
        return new BranchTransitionHandler();
    }

    @Override
    protected Class<BranchStatus> stateType() {
        return BranchStatus.class;
    }

    @Override
    protected Map<BranchStatus, Set<BranchStatus>> expectedTransitions() {
        return Map.of(DRAFT, Set.of(ACTIVE));    // #2
    }

    @Override
    protected Set<BranchStatus> expectedInitialStates() {
        return Set.of(DRAFT);                    // #1
    }
}
