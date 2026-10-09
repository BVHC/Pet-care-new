package com.petcare.module.branch.fsm;

import static com.petcare.module.branch.entity.BranchStatus.ACTIVE;
import static com.petcare.module.branch.entity.BranchStatus.DRAFT;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.platform.fsm.StateMachineBase;

/** Bảng "2. Chi nhánh" của docs/03-state-machines.md. Tạm ngừng / đóng cửa (UC13) là tầng 3 nên chưa có cạnh. */
@Component
public class BranchTransitionHandler extends StateMachineBase<BranchStatus> {

    @Override
    public Map<BranchStatus, Set<BranchStatus>> allowedTransitions() {
        return Map.of(DRAFT, Set.of(ACTIVE));   // #2 kích hoạt
    }

    @Override
    public Set<BranchStatus> initialStates() {
        return Set.of(DRAFT);                   // #1 tạo chi nhánh
    }
}
