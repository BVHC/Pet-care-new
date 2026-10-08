package com.petcare.module.identity.dto;

import java.time.Instant;

/**
 * Kết quả gửi OTP (identity-v1 {@code OtpSentResponse}). {@code resendAvailableAt}: mốc được gửi lại (BR-TK-07).
 * {@code maskedEmail}: email nhận mã đã che, chỉ có ở luồng liên kết hồ sơ; các luồng khác để {@code null}.
 */
public record OtpSentResponse(Instant resendAvailableAt, String maskedEmail) {
}
