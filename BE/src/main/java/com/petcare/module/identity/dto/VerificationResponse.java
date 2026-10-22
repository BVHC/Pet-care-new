package com.petcare.module.identity.dto;

import com.petcare.module.identity.entity.AccountStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả xác thực OTP đăng ký (identity-v1 {@code VerificationResponse}). {@code linkDecisionPending}: hồ sơ online
 * bị đặt cờ chờ quyết định liên kết (BR-TK-19) — FE hiện lời đề nghị liên kết sau khi đăng nhập.
 */
public record VerificationResponse(
        @Schema(description = "Mã tài khoản", example = "42")
        Long accountId,

        @Schema(description = "Trạng thái sau khi xác thực", example = "ACTIVE")
        AccountStatus status,

        @Schema(description = "SĐT trùng hồ sơ tại quầy chưa liên kết: hỏi khách có liên kết không (BR-TK-19)",
                example = "false")
        boolean linkDecisionPending) {
}
