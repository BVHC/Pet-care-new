package com.petcare.module.workforce.fsm;

import static com.petcare.module.workforce.entity.ShiftRequestStatus.APPROVED;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.PENDING;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.REJECTED;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.WITHDRAWN;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "11. Yêu cầu nghỉ / đổi ca" của docs/03-state-machines.md. #1 — → {@code PENDING}; #2 {@code → WITHDRAWN};
 * #3 {@code → APPROVED}; #4 (BRANCH_MANAGER từ chối) và #5 (phân ca không còn hiệu lực) cùng một cạnh
 * {@code PENDING → REJECTED}. {@code APPROVED}, {@code REJECTED}, {@code WITHDRAWN} là FINAL nên không có khóa; bảng
 * này không có cạnh {@code X → X}.
 */
@Component
public class ShiftRequestTransitionHandler extends StateMachineBase<ShiftRequestStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "yêu cầu nghỉ / đổi ca";

    @Override
    public Map<ShiftRequestStatus, Set<ShiftRequestStatus>> allowedTransitions() {
        return Map.of(PENDING, Set.of(WITHDRAWN,     // #2
                APPROVED,                            // #3
                REJECTED));                          // #4, #5
    }

    @Override
    public Set<ShiftRequestStatus> initialStates() {
        return Set.of(PENDING);                      // #1
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
