package com.petcare.module.organization.fsm;

import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.StoreStatus.ACTIVE;
import static com.petcare.platform.enums.StoreStatus.ARCHIVED;
import static com.petcare.platform.enums.StoreStatus.DEACTIVATED;
import static com.petcare.platform.enums.StoreStatus.DRAFT;
import static com.petcare.platform.enums.StoreStatus.SUSPENDED;

/**
 * FSM 2 (Store) — docs/03-state-machines.md §2. Khai báo đầy đủ transition map dù phạm vi lần
 * này (ActivateStore) chỉ dùng 3 cạnh DRAFT/SUSPENDED/DEACTIVATED -> ACTIVE — các cạnh còn lại
 * (SuspendStore, DeactivateStore, ArchiveStore) dành cho triển khai sau, tái dùng đúng class này
 * (docs/convention/backend/05-fsm-pattern.md), cùng pattern AccountTransitionHandler.
 * ARCHIVED không có key trong map — Terminal State tuyệt đối (Technical Invariant #4), không
 * transition nào rời khỏi ARCHIVED; StateMachineBase#validateTransition tự trả Set rỗng qua
 * getOrDefault nên không cần khai Map.of(ARCHIVED, Set.of()) tường minh.
 */
@Component
public class StoreTransitionHandler extends StateMachineBase<StoreStatus> {

    @Override
    public Map<StoreStatus, Set<StoreStatus>> allowedTransitions() {
        return Map.of(
                DRAFT, Set.of(ACTIVE),
                ACTIVE, Set.of(SUSPENDED, DEACTIVATED),
                SUSPENDED, Set.of(ACTIVE, ARCHIVED),
                DEACTIVATED, Set.of(ACTIVE, ARCHIVED)
        );
    }
}
