package com.petcare.module.identity.dto;

import com.petcare.module.identity.entity.AccountStatus;

/**
 * Kết quả xác thực OTP đăng ký (identity-v1 {@code VerificationResponse}). {@code linkDecisionPending}: hồ sơ online
 * bị đặt cờ chờ quyết định liên kết (BR-TK-19) — FE hiện lời đề nghị liên kết sau khi đăng nhập.
 */
public record VerificationResponse(Long accountId, AccountStatus status, boolean linkDecisionPending) {
}
