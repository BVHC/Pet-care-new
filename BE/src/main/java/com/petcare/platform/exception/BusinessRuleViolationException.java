package com.petcare.platform.exception;

/**
 * Vi phạm RULE-ID ở docs/02-business-rules.md (docs/convention/backend/04-exception-handling.md §4.1).
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String ruleId;

    public BusinessRuleViolationException(String ruleId, String message) {
        super(message);
        this.ruleId = ruleId;
    }

    public String getRuleId() {
        return ruleId;
    }
}
