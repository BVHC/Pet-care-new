package com.petcare.module.workforce.fsm;

import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.ABSENT;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.CANCELLED;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.ON_LEAVE;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.SCHEDULED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "12. Phân ca" của docs/03-state-machines.md. #1 — → {@code SCHEDULED}; #2 (hủy phân ca), #6 (nhân viên rời
 * chi nhánh), #7 (nhân viên sửa đăng ký tuần) cùng một cạnh {@code SCHEDULED → CANCELLED}; #3 {@code → ON_LEAVE} (SYS ←
 * Yêu cầu#3); #4 {@code → ABSENT}; #5 {@code SCHEDULED → SCHEDULED} (đổi ca, SYS ← Yêu cầu#3). {@code ON_LEAVE},
 * {@code ABSENT}, {@code CANCELLED} là FINAL nên không có khóa.
 */
@Component
public class ShiftAssignmentTransitionHandler extends StateMachineBase<ShiftAssignmentStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "phân ca";

    @Override
    public Map<ShiftAssignmentStatus, Set<ShiftAssignmentStatus>> allowedTransitions() {
        return Map.of(SCHEDULED, Set.of(SCHEDULED,   // #5 đổi ca
                ON_LEAVE,                            // #3
                ABSENT,                              // #4
                CANCELLED));                         // #2, #6, #7
    }

    @Override
    public Set<ShiftAssignmentStatus> initialStates() {
        return Set.of(SCHEDULED);                    // #1
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
