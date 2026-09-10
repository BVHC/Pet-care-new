package com.petcare.platform.exception;

/**
 * Vi phạm RULE-02-01 (Scope mismatch) (docs/convention/backend/04-exception-handling.md §4.1).
 */
public class AccessDeniedScopeException extends RuntimeException {

    private final String requiredScope;
    private final String actualScope;

    public AccessDeniedScopeException(String requiredScope, String actualScope) {
        super(String.format("Access denied: required scope %s, actual scope %s", requiredScope, actualScope));
        this.requiredScope = requiredScope;
        this.actualScope = actualScope;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public String getActualScope() {
        return actualScope;
    }
}
