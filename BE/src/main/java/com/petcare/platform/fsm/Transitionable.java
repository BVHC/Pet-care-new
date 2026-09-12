package com.petcare.platform.fsm;

import java.util.Map;
import java.util.Set;

/**
 * docs/convention/backend/05-fsm-pattern.md — Enum State + Transition Map, không dùng Spring StateMachine.
 */
public interface Transitionable<S extends Enum<S>> {
    Map<S, Set<S>> allowedTransitions();
}
