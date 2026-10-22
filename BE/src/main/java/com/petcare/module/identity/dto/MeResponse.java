package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Người đang đăng nhập (identity-v1 {@code MeResponse}, {@code GET /api/me}). Nhân viên: {@code staffProfile} có,
 * {@code customerId} null, {@code linkDecisionPending} false. Khách: {@code staffProfile} null, {@code customerId} là
 * hồ sơ khách gắn tài khoản (BR-KH-01), {@code linkDecisionPending} là cờ chờ quyết định liên kết (BR-TK-19) — FE ẩn
 * chức năng cần hồ sơ và nhắc lại lời đề nghị.
 */
public record MeResponse(
        @Schema(description = "Tài khoản đang đăng nhập")
        AccountSummary account,

        @Schema(description = "Hồ sơ nhân viên; null với khách")
        StaffProfileResponse staffProfile,

        @Schema(description = "Hồ sơ khách gắn với tài khoản; null với nhân viên", example = "315")
        Long customerId,

        @Schema(description = "Còn cờ chờ quyết định liên kết (BR-TK-19); luôn false với nhân viên", example = "false")
        boolean linkDecisionPending) {
}
