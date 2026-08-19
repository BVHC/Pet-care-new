package com.petcare.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    // Common exceptions
    public static BusinessException notFound(String entity, Long id) {
        return new BusinessException(
            entity.toUpperCase() + "_NOT_FOUND",
            entity + " not found: " + id,
            HttpStatus.NOT_FOUND
        );
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException("BAD_REQUEST", message, HttpStatus.BAD_REQUEST);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException("CONFLICT", message, HttpStatus.CONFLICT);
    }

    public static BusinessException invalidStateTransition(String from, String to) {
        return new BusinessException(
            "INVALID_STATE_TRANSITION",
            "Cannot transition from " + from + " to " + to,
            HttpStatus.BAD_REQUEST
        );
    }
}
