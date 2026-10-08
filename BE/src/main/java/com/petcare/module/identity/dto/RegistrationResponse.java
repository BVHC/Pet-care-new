package com.petcare.module.identity.dto;

import java.time.Instant;

import com.petcare.module.identity.entity.AccountStatus;

/** Kết quả đăng ký (identity-v1 {@code RegistrationResponse}). {@code otpResendAvailableAt}: mốc được gửi lại OTP (BR-TK-07). */
public record RegistrationResponse(Long accountId, String email, AccountStatus status, Instant otpResendAvailableAt) {
}
