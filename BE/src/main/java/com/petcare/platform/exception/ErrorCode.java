package com.petcare.platform.exception;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi trả về trong {@code ErrorResponse.errorCode} và HTTP status tương ứng.
 * Sáu mã đầu theo docs/convention/backend/04-exception-handling.md §4.3; các mã còn lại phủ lỗi
 * tầng web/security để mọi lỗi đều đi qua cùng một envelope.
 */
public enum ErrorCode {

    BUSINESS_RULE_VIOLATION(HttpStatus.BAD_REQUEST),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACCESS_DENIED_SCOPE_MISMATCH(HttpStatus.FORBIDDEN),
    CONCURRENCY_CONFLICT(HttpStatus.CONFLICT),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
