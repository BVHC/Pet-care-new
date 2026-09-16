package com.petcare.platform.fsm;

import com.petcare.platform.exception.InvalidStateTransitionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Base test class cho mọi {Entity}TransitionHandler (docs/convention/backend/09-testing.md).
 * Đây là class đầu tiên trong repo — mọi module có FSM sau này (17 module) tái dùng.
 */
public abstract class FsmTransitionTestBase<S extends Enum<S>> {

    protected abstract StateMachineBase<S> handler();

    protected void assertValidTransition(S from, S to) {
        assertDoesNotThrow(() -> handler().validateTransition(from, to));
    }

    protected void assertInvalidTransition(S from, S to) {
        assertThrows(InvalidStateTransitionException.class, () -> handler().validateTransition(from, to));
    }
}
