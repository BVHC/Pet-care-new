package com.petcare.platform.fsm;

import com.petcare.platform.exception.InvalidStateTransitionException;

import java.util.Set;

/**
 * docs/convention/backend/05-fsm-pattern.md — mọi {Entity}TransitionHandler ở module/<feature>/fsm/
 * sẽ extend class này; transition map lấy trực tiếp từ bảng mermaid stateDiagram-v2 tương ứng
 * trong docs/03-state-machines.md, không tự suy diễn thêm cạnh.
 */
public abstract class StateMachineBase<S extends Enum<S>> implements Transitionable<S> {

    public void validateTransition(S from, S to) {
        Set<S> allowed = allowedTransitions().getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStateTransitionException(getClass().getSimpleName(), from.name(), to.name());
        }
    }
}
