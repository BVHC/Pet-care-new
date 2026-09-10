package com.petcare.platform.exception;

/**
 * Optimistic lock conflict (docs/convention/backend/04-exception-handling.md §4.1).
 */
public class ConcurrencyConflictException extends RuntimeException {

    private final String resourceType;
    private final Object resourceId;

    public ConcurrencyConflictException(String resourceType, Object resourceId) {
        super(String.format("Concurrency conflict on %s: %s", resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Object getResourceId() {
        return resourceId;
    }
}
