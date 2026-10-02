package com.petcare.platform.fsm;

import java.util.Set;

import org.springframework.util.ClassUtils;

import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Lớp cha của mọi {@code {Entity}TransitionHandler} ở {@code module/<feature>/fsm/}.
 * Guard nghiệp vụ (ném {@code BusinessRuleViolationException}) phải chạy <b>trước</b> các hàm validate ở đây.
 */
public abstract class StateMachineBase<S extends Enum<S>> implements Transitionable<S> {

    public boolean canTransition(S from, S to) {
        return allowedTransitions().getOrDefault(from, Set.of()).contains(to);
    }

    public void validateTransition(S from, S to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(fsmName(), from.name(), to.name());
        }
    }

    public void validateInitial(S to) {
        if (!initialStates().contains(to)) {
            throw new InvalidStateTransitionException(fsmName(), null, to.name());
        }
    }

    /** Tên lớp thật, kể cả khi handler bị Spring proxy bằng CGLIB. */
    protected String fsmName() {
        return ClassUtils.getUserClass(getClass()).getSimpleName();
    }
}
