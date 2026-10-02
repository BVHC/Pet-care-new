package com.petcare.platform.fsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Kiểm tra {@link StateMachineBase} bằng một FSM giả có cạnh {@code X → X}, đồng thời kiểm chứng
 * {@link FsmTransitionTestBase} phát hiện được handler lệch bảng.
 */
class StateMachineBaseTest extends FsmTransitionTestBase<StateMachineBaseTest.SampleStatus> {

    enum SampleStatus { DRAFT, ACTIVE, CLOSED }

    static class SampleTransitionHandler extends StateMachineBase<SampleStatus> {

        @Override
        public Map<SampleStatus, Set<SampleStatus>> allowedTransitions() {
            return Map.of(
                    SampleStatus.DRAFT, Set.of(SampleStatus.DRAFT, SampleStatus.ACTIVE),
                    SampleStatus.ACTIVE, Set.of(SampleStatus.CLOSED));
        }

        @Override
        public Set<SampleStatus> initialStates() {
            return Set.of(SampleStatus.DRAFT);
        }
    }

    private final SampleTransitionHandler handler = new SampleTransitionHandler();

    @Override
    protected StateMachineBase<SampleStatus> handler() {
        return handler;
    }

    @Override
    protected Class<SampleStatus> stateType() {
        return SampleStatus.class;
    }

    @Override
    protected Map<SampleStatus, Set<SampleStatus>> expectedTransitions() {
        return Map.of(
                SampleStatus.DRAFT, Set.of(SampleStatus.DRAFT, SampleStatus.ACTIVE),
                SampleStatus.ACTIVE, Set.of(SampleStatus.CLOSED));
    }

    @Override
    protected Set<SampleStatus> expectedInitialStates() {
        return Set.of(SampleStatus.DRAFT);
    }

    @Test
    void invalidTransitionCarriesFsmNameAndStates() {
        assertThatThrownBy(() -> handler.validateTransition(SampleStatus.CLOSED, SampleStatus.ACTIVE))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(ex.getFsmName()).isEqualTo("SampleTransitionHandler");
                    assertThat(ex.getFrom()).isEqualTo("CLOSED");
                    assertThat(ex.getTo()).isEqualTo("ACTIVE");
                });
    }

    @Test
    void invalidInitialStateHasNoFromState() {
        assertThatThrownBy(() -> handler.validateInitial(SampleStatus.ACTIVE))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class, ex -> {
                    assertThat(ex.getFrom()).isNull();
                    assertThat(ex.getMessage()).contains("từ — sang ACTIVE");
                });
    }

    @Test
    void canTransitionReflectsWhitelist() {
        assertThat(handler.canTransition(SampleStatus.DRAFT, SampleStatus.DRAFT)).isTrue();
        assertThat(handler.canTransition(SampleStatus.CLOSED, SampleStatus.CLOSED)).isFalse();
    }

    /** Handler thêm cạnh CLOSED → DRAFT không có trong bảng: base phải làm fail đúng cặp đó. */
    @Test
    void baseDetectsHandlerWithExtraEdge() {
        FsmTransitionTestBase<SampleStatus> drifted = new StateMachineBaseTest() {
            @Override
            protected StateMachineBase<SampleStatus> handler() {
                return new SampleTransitionHandler() {
                    @Override
                    public Map<SampleStatus, Set<SampleStatus>> allowedTransitions() {
                        return Map.of(
                                SampleStatus.DRAFT, Set.of(SampleStatus.DRAFT, SampleStatus.ACTIVE),
                                SampleStatus.ACTIVE, Set.of(SampleStatus.CLOSED),
                                SampleStatus.CLOSED, Set.of(SampleStatus.DRAFT));
                    }
                };
            }
        };

        List<String> failed = drifted.everyTransitionPairMatchesSpec()
                .filter(StateMachineBaseTest::fails)
                .map(DynamicTest::getDisplayName)
                .toList();

        assertThat(failed).containsExactly("CLOSED → DRAFT bị từ chối");
    }

    private static boolean fails(DynamicTest test) {
        try {
            test.getExecutable().execute();
            return false;
        } catch (AssertionFailedError e) {
            return true;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
