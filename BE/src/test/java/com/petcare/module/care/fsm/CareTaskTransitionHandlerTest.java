package com.petcare.module.care.fsm;

import static com.petcare.module.care.entity.CareTaskStatus.CANCELLED;
import static com.petcare.module.care.entity.CareTaskStatus.DONE;
import static com.petcare.module.care.entity.CareTaskStatus.OPEN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "10. Care Task" (docs/03-state-machines.md L226–233): #1 — → OPEN; #2 OPEN → DONE; #3, #4,
 * #5, #6 OPEN → CANCELLED. DONE, CANCELLED là FINAL; không có cạnh X → X.
 */
class CareTaskTransitionHandlerTest extends FsmTransitionTestBase<CareTaskStatus> {

    @Override
    protected StateMachineBase<CareTaskStatus> handler() {
        return new CareTaskTransitionHandler();
    }

    @Override
    protected Class<CareTaskStatus> stateType() {
        return CareTaskStatus.class;
    }

    @Override
    protected Map<CareTaskStatus, Set<CareTaskStatus>> expectedTransitions() {
        return Map.of(OPEN, Set.of(DONE, CANCELLED));       // #2 | #3, #4, #5, #6
    }

    @Override
    protected Set<CareTaskStatus> expectedInitialStates() {
        return Set.of(OPEN);                                // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        CareTaskTransitionHandler handler = new CareTaskTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(DONE, CANCELLED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái việc chăm sóc khách từ DONE sang CANCELLED");
        assertThatThrownBy(() -> handler.validateInitial(DONE))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái việc chăm sóc khách từ — sang DONE");
    }
}
