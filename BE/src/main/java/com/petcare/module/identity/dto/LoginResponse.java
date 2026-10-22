package com.petcare.module.identity.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng nhập (identity-v1 {@code LoginResponse}). {@code expiresAt}: hạn tuyệt đối của phiên
 * ({@code session.ttl_hours} [CFG], không gia hạn — docs/adr/0003). {@code linkDecisionPending}: hồ sơ khách còn cờ chờ
 * quyết định liên kết (BR-TK-19) — FE nhắc lại lời đề nghị; luôn {@code false} với nhân viên.
 */
public record LoginResponse(
        @Schema(description = "Bearer token gửi trong header Authorization",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.sig")
        String accessToken,

        @Schema(description = "Hạn tuyệt đối của phiên (session.ttl_hours [CFG], không gia hạn)",
                example = "2026-10-14T14:30:00Z")
        Instant expiresAt,

        @Schema(description = "Tài khoản vừa đăng nhập")
        AccountSummary account,

        @Schema(description = "Hồ sơ khách còn chờ quyết định liên kết (BR-TK-19); luôn false với nhân viên",
                example = "false")
        boolean linkDecisionPending) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, expiresAt=" + expiresAt + ", account=" + account
                + ", linkDecisionPending=" + linkDecisionPending + "]";
    }
}
