package com.petcare.platform.model;

import java.time.Instant;

import com.petcare.platform.config.TraceContext;
import com.petcare.platform.exception.ErrorCode;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope lỗi duy nhất của hệ thống, đủ 6 field bắt buộc
 * (docs/convention/backend/04-exception-handling.md §4.3). Chỉ {@code GlobalExceptionHandler} tạo ra.
 */
@Schema(description = "Envelope lỗi duy nhất, do GlobalExceptionHandler sinh. Vi phạm business rule: errorCode ="
        + " BUSINESS_RULE_VIOLATION, message kết thúc bằng mã rule, ví dụ \"… (BR-LH-05)\".")
public record ErrorResponse(
        @Schema(description = "Luôn false", example = "false")
        boolean success,

        @Schema(description = "Mã lỗi (platform/exception/ErrorCode)", example = "BUSINESS_RULE_VIOLATION")
        String errorCode,

        @Schema(description = "Thông điệp tiếng Việt hiển thị được; với BUSINESS_RULE_VIOLATION kết thúc bằng "
                + "\" (BR-…)\"",
                example = "Email đã được sử dụng (BR-TK-01)")
        String message,

        @Schema(description = "HTTP status", example = "400")
        int statusCode,

        @Schema(description = "Thời điểm lỗi (UTC)", type = "string", format = "date-time",
                example = "2026-10-14T02:30:00Z")
        Instant timestamp,

        @Schema(description = "Mã truy vết, trùng header X-Trace-Id và log của backend",
                example = "9f1c2d7e4b6a4c3e")
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
