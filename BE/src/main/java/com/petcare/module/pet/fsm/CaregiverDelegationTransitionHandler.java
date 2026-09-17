package com.petcare.module.pet.fsm;

import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.CaregiverStatus.ACTIVE;
import static com.petcare.platform.enums.CaregiverStatus.EXPIRED;
import static com.petcare.platform.enums.CaregiverStatus.INVITED;
import static com.petcare.platform.enums.CaregiverStatus.REJECTED;
import static com.petcare.platform.enums.CaregiverStatus.REVOKED;

/**
 * FSM-3 (CaregiverStatus) — docs/03-state-machines.md §3.
 * Cạnh INVITED -> REVOKED (chủ hủy lời mời đang treo) là bổ sung đã được duyệt
 * 2026-09-16, xem spec D-04; đặc tả mermaid đã cập nhật cùng đợt.
 * REJECTED / EXPIRED / REVOKED là trạng thái cuối, không có cạnh ra.
 */
@Component
public class CaregiverDelegationTransitionHandler extends StateMachineBase<CaregiverStatus> {

    @Override
    public Map<CaregiverStatus, Set<CaregiverStatus>> allowedTransitions() {
        return Map.of(
                INVITED, Set.of(ACTIVE, REJECTED, EXPIRED, REVOKED),
                ACTIVE, Set.of(REVOKED, EXPIRED)
        );
    }
}
