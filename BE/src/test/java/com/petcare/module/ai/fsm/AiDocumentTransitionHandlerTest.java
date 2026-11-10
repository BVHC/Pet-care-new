package com.petcare.module.ai.fsm;

import static com.petcare.module.ai.entity.DocumentStatus.ARCHIVED;
import static com.petcare.module.ai.entity.DocumentStatus.DRAFT;
import static com.petcare.module.ai.entity.DocumentStatus.PUBLISHED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng FSM #13 ở docs/07-ai-subsystem.md §9: #1 — → DRAFT; #2 DRAFT → PUBLISHED; #3 và #7
 * PUBLISHED → PUBLISHED; #4 PUBLISHED → ARCHIVED; #5 ARCHIVED → PUBLISHED; #6 DRAFT → ARCHIVED. Không có cạnh
 * ARCHIVED → ARCHIVED hay DRAFT → DRAFT; trạng thái khởi tạo duy nhất của bảng là DRAFT.
 */
class AiDocumentTransitionHandlerTest extends FsmTransitionTestBase<DocumentStatus> {

    @Override
    protected StateMachineBase<DocumentStatus> handler() {
        return new AiDocumentTransitionHandler();
    }

    @Override
    protected Class<DocumentStatus> stateType() {
        return DocumentStatus.class;
    }

    @Override
    protected Map<DocumentStatus, Set<DocumentStatus>> expectedTransitions() {
        return Map.of(
                DRAFT, Set.of(PUBLISHED, ARCHIVED),         // #2 | #6
                PUBLISHED, Set.of(PUBLISHED, ARCHIVED),     // #3, #7 | #4
                ARCHIVED, Set.of(PUBLISHED));               // #5
    }

    @Override
    protected Set<DocumentStatus> expectedInitialStates() {
        return Set.of(DRAFT);                               // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        AiDocumentTransitionHandler handler = new AiDocumentTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(ARCHIVED, ARCHIVED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài liệu tri thức từ ARCHIVED sang ARCHIVED");
        assertThatThrownBy(() -> handler.validateInitial(PUBLISHED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài liệu tri thức từ — sang PUBLISHED");
    }
}
