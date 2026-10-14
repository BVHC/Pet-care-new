package com.petcare.module.identity.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả gửi OTP (identity-v1 {@code OtpSentResponse}). {@code resendAvailableAt}: mốc được gửi lại (BR-TK-07).
 * {@code maskedEmail}: email nhận mã đã che, chỉ có ở luồng liên kết hồ sơ; các luồng khác để {@code null}.
 */
public record OtpSentResponse(
        @Schema(description = "Mốc được gửi lại mã (BR-TK-07)", example = "2026-10-14T02:31:00Z")
        Instant resendAvailableAt,

        @Schema(description = "Email nhận mã đã che; null khi không cần hiển thị", example = "n***@gmail.com")
        String maskedEmail) {
}
