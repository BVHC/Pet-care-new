package com.petcare.platform.model;

import java.time.Instant;

import com.petcare.platform.config.TraceContext;
import com.petcare.platform.exception.ErrorCode;

/**
 * Envelope lỗi duy nhất của hệ thống, đủ 6 field bắt buộc
 * (docs/convention/backend/04-exception-handling.md §4.3). Chỉ {@code GlobalExceptionHandler} tạo ra.
 */
public record ErrorResponse(
        boolean success,
        String errorCode,
        String message,
        int statusCode,
        Instant timestamp,
        String traceId) {

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(
                false,
                errorCode.name(),
                message,
                errorCode.status().value(),
                Instant.now(),
                TraceContext.current());
    }
}
