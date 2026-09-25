package com.petcare.module.procurement.fsm;

import com.petcare.platform.enums.PurchaseRequestStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.PurchaseRequestStatus.APPROVED;
import static com.petcare.platform.enums.PurchaseRequestStatus.CANCELLED;
import static com.petcare.platform.enums.PurchaseRequestStatus.DRAFT;
import static com.petcare.platform.enums.PurchaseRequestStatus.REJECTED;
import static com.petcare.platform.enums.PurchaseRequestStatus.SUBMITTED;

/**
 * FSM-12 (docs/03-state-machines.md §12) — copy nguyên bản mermaid, không tự suy diễn thêm cạnh
 * (docs/convention/backend/05-fsm-pattern.md). {@code APPROVED}/{@code REJECTED}/{@code CANCELLED}
 * là Terminal State nên vắng mặt trong map — {@link StateMachineBase#validateTransition} tự trả
 * tập rỗng cho các state đó.
 */
@Component
public class PurchaseRequestTransitionHandler extends StateMachineBase<PurchaseRequestStatus> {

    @Override
    public Map<PurchaseRequestStatus, Set<PurchaseRequestStatus>> allowedTransitions() {
        return Map.of(
                DRAFT, Set.of(SUBMITTED, CANCELLED),
                SUBMITTED, Set.of(APPROVED, REJECTED, CANCELLED)
        );
    }
}
