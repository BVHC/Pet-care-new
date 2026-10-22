package com.petcare.module.visit.fsm;

import static com.petcare.module.visit.entity.VisitStatus.CANCELLED;
import static com.petcare.module.visit.entity.VisitStatus.COMPLETED;
import static com.petcare.module.visit.entity.VisitStatus.IN_PROGRESS;
import static com.petcare.module.visit.entity.VisitStatus.WAITING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "4. Visit" (docs/03-state-machines.md L100–107): #1 — → WAITING; #2 WAITING → WAITING;
 * #3 WAITING → IN_PROGRESS; #4 IN_PROGRESS → IN_PROGRESS; #5 IN_PROGRESS → COMPLETED; #6 WAITING → CANCELLED.
 * COMPLETED, CANCELLED là FINAL.
 */
class VisitTransitionHandlerTest extends FsmTransitionTestBase<VisitStatus> {

    @Override
    protected StateMachineBase<VisitStatus> handler() {
        return new VisitTransitionHandler();
    }

    @Override
    protected Class<VisitStatus> stateType() {
        return VisitStatus.class;
    }

    @Override
    protected Map<VisitStatus, Set<VisitStatus>> expectedTransitions() {
        return Map.of(
                WAITING, Set.of(WAITING, IN_PROGRESS, CANCELLED),          // #2, #3, #6
                IN_PROGRESS, Set.of(IN_PROGRESS, COMPLETED));               // #4, #5
    }

    @Override
    protected Set<VisitStatus> expectedInitialStates() {
        return Set.of(WAITING);                                            // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        VisitTransitionHandler handler = new VisitTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(IN_PROGRESS, CANCELLED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang CANCELLED");
        assertThatThrownBy(() -> handler.validateInitial(IN_PROGRESS))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lượt tiếp nhận từ — sang IN_PROGRESS");
    }
}
