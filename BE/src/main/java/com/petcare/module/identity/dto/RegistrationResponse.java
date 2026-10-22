package com.petcare.module.identity.dto;

import java.time.Instant;

import com.petcare.module.identity.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả đăng ký (identity-v1 {@code RegistrationResponse}). {@code otpResendAvailableAt}: mốc được gửi lại OTP (BR-TK-07). */
public record RegistrationResponse(
        @Schema(description = "Mã tài khoản vừa tạo", example = "42")
        Long accountId,

        @Schema(description = "Email đăng ký", example = "lan.nguyen@gmail.com")
        String email,

        @Schema(description = "Luôn PENDING tới khi xác thực OTP", example = "PENDING")
        AccountStatus status,

        @Schema(description = "Mốc được gửi lại OTP (BR-TK-07)", example = "2026-10-14T02:31:00Z")
        Instant otpResendAvailableAt) {
}
