package com.petcare.platform.model;

import java.time.Instant;

/**
 * Envelope lỗi thống nhất trả về từ GlobalExceptionHandler
 * (docs/convention/backend/04-exception-handling.md §4.3 — đủ 6 field bắt buộc).
 */
public record ErrorResponse(
        boolean success,
        String errorCode,
        String message,
        int statusCode,
        Instant timestamp,
        String traceId
) {
    public static ErrorResponse of(String errorCode, String message, int statusCode, String traceId) {
        return new ErrorResponse(false, errorCode, message, statusCode, Instant.now(), traceId);
    }
}
