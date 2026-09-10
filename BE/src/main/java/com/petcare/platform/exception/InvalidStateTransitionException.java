package com.petcare.platform.exception;

/**
 * Transition FSM không hợp lệ (docs/convention/backend/04-exception-handling.md §4.1,
 * docs/convention/backend/05-fsm-pattern.md).
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final String fsmName;
    private final String from;
    private final String to;

    public InvalidStateTransitionException(String fsmName, String from, String to) {
        super(String.format("Invalid transition in %s: %s -> %s", fsmName, from, to));
        this.fsmName = fsmName;
        this.from = from;
        this.to = to;
    }

    public String getFsmName() {
        return fsmName;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
