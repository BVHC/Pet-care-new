package com.petcare.platform.fsm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Base test cho mọi FSM (docs/convention/backend/09-testing.md). Lớp con khai báo một bản sao <b>độc lập</b>
 * của bảng chuyển trạng thái trong docs/03-state-machines.md — không đọc lại từ handler, nếu không test sẽ
 * tự khẳng định chính nó. Base tự duyệt mọi cặp {@code from × to} của enum nên không thể bỏ sót cặp nào.
 */
public abstract class FsmTransitionTestBase<S extends Enum<S>> {

    protected abstract StateMachineBase<S> handler();

    protected abstract Class<S> stateType();

    /** Bảng 03: cạnh hợp lệ, gồm cả dòng {@code X → X}. */
    protected abstract Map<S, Set<S>> expectedTransitions();

    /** Bảng 03: các dòng {@code — → X}. */
    protected abstract Set<S> expectedInitialStates();

    protected void assertValidTransition(S from, S to) {
        assertDoesNotThrow(() -> handler().validateTransition(from, to));
    }

    protected void assertInvalidTransition(S from, S to) {
        assertThrows(InvalidStateTransitionException.class, () -> handler().validateTransition(from, to));
    }

    @TestFactory
    Stream<DynamicTest> everyTransitionPairMatchesSpec() {
        S[] states = stateType().getEnumConstants();
        return Arrays.stream(states).flatMap(from -> Arrays.stream(states).map(to -> {
            boolean allowed = expectedTransitions().getOrDefault(from, Set.of()).contains(to);
            return DynamicTest.dynamicTest(from + " → " + to + (allowed ? " hợp lệ" : " bị từ chối"), () -> {
                if (allowed) {
                    assertValidTransition(from, to);
                } else {
                    assertInvalidTransition(from, to);
                }
            });
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyInitialStateMatchesSpec() {
        return Arrays.stream(stateType().getEnumConstants()).map(state -> {
            boolean allowed = expectedInitialStates().contains(state);
            return DynamicTest.dynamicTest("— → " + state + (allowed ? " hợp lệ" : " bị từ chối"), () -> {
                if (allowed) {
                    assertDoesNotThrow(() -> handler().validateInitial(state));
                } else {
                    assertThrows(InvalidStateTransitionException.class, () -> handler().validateInitial(state));
                }
            });
        });
    }
}
