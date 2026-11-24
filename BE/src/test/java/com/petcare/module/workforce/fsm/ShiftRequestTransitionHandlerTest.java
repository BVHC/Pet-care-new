package com.petcare.module.workforce.fsm;

import static com.petcare.module.workforce.entity.ShiftRequestStatus.APPROVED;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.PENDING;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.REJECTED;
import static com.petcare.module.workforce.entity.ShiftRequestStatus.WITHDRAWN;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "11. Yêu cầu nghỉ / đổi ca" (docs/03-state-machines.md): #1 — → PENDING; #2 PENDING →
 * WITHDRAWN; #3 PENDING → APPROVED; #4, #5 PENDING → REJECTED. APPROVED, REJECTED, WITHDRAWN là FINAL; bảng này
 * <b>không</b> có cạnh X → X.
 */
class ShiftRequestTransitionHandlerTest extends FsmTransitionTestBase<ShiftRequestStatus> {

    @Override
    protected StateMachineBase<ShiftRequestStatus> handler() {
        return new ShiftRequestTransitionHandler();
    }

    @Override
    protected Class<ShiftRequestStatus> stateType() {
        return ShiftRequestStatus.class;
    }

    @Override
    protected Map<ShiftRequestStatus, Set<ShiftRequestStatus>> expectedTransitions() {
        return Map.of(PENDING, Set.of(WITHDRAWN, APPROVED, REJECTED));              // #2 | #3 | #4, #5
    }

    @Override
    protected Set<ShiftRequestStatus> expectedInitialStates() {
        return Set.of(PENDING);                                                      // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        ShiftRequestTransitionHandler handler = new ShiftRequestTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(APPROVED, REJECTED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ APPROVED sang REJECTED");
        assertThatThrownBy(() -> handler.validateInitial(WITHDRAWN))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ — sang WITHDRAWN");
    }
}
