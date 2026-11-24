package com.petcare.module.workforce.fsm;

import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.ABSENT;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.CANCELLED;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.ON_LEAVE;
import static com.petcare.module.workforce.entity.ShiftAssignmentStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "12. Phân ca" (docs/03-state-machines.md): #1 — → SCHEDULED; #2, #6, #7 SCHEDULED →
 * CANCELLED; #3 SCHEDULED → ON_LEAVE; #4 SCHEDULED → ABSENT; #5 SCHEDULED → SCHEDULED. ON_LEAVE, ABSENT, CANCELLED là
 * FINAL.
 */
class ShiftAssignmentTransitionHandlerTest extends FsmTransitionTestBase<ShiftAssignmentStatus> {

    @Override
    protected StateMachineBase<ShiftAssignmentStatus> handler() {
        return new ShiftAssignmentTransitionHandler();
    }

    @Override
    protected Class<ShiftAssignmentStatus> stateType() {
        return ShiftAssignmentStatus.class;
    }

    @Override
    protected Map<ShiftAssignmentStatus, Set<ShiftAssignmentStatus>> expectedTransitions() {
        return Map.of(SCHEDULED, Set.of(SCHEDULED, ON_LEAVE, ABSENT, CANCELLED));  // #5 | #3 | #4 | #2, #6, #7
    }

    @Override
    protected Set<ShiftAssignmentStatus> expectedInitialStates() {
        return Set.of(SCHEDULED);                                                   // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        ShiftAssignmentTransitionHandler handler = new ShiftAssignmentTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(ON_LEAVE, CANCELLED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái phân ca từ ON_LEAVE sang CANCELLED");
        assertThatThrownBy(() -> handler.validateInitial(ABSENT))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái phân ca từ — sang ABSENT");
    }
}
