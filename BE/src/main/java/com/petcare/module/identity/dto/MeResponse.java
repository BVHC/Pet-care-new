package com.petcare.module.identity.dto;

/**
 * Người đang đăng nhập (identity-v1 {@code MeResponse}, {@code GET /api/me}). Nhân viên: {@code staffProfile} có,
 * {@code customerId} null, {@code linkDecisionPending} false. Khách: {@code staffProfile} null, {@code customerId} là
 * hồ sơ khách gắn tài khoản (BR-KH-01), {@code linkDecisionPending} là cờ chờ quyết định liên kết (BR-TK-19) — FE ẩn
 * chức năng cần hồ sơ và nhắc lại lời đề nghị.
 */
public record MeResponse(
        AccountSummary account,
        StaffProfileResponse staffProfile,
        Long customerId,
        boolean linkDecisionPending) {
}
