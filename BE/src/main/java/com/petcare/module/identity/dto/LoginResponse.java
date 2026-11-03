package com.petcare.module.identity.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả đăng nhập (identity-v1 {@code LoginResponse}). {@code expiresAt}: hạn tuyệt đối của phiên
 * ({@code session.ttl_hours} [CFG], không gia hạn — docs/adr/0003). {@code accessTokenExpiresAt}: hạn của
 * {@code accessToken} ({@code session.access_ttl_minutes} [CFG]); hết hạn thì đổi bằng {@code refreshToken}
 * ({@code POST /auth/refresh}, docs/adr/0091). {@code linkDecisionPending}: hồ sơ khách còn cờ chờ
 * quyết định liên kết (BR-TK-19) — FE nhắc lại lời đề nghị; luôn {@code false} với nhân viên.
 */
public record LoginResponse(
        @Schema(description = "Bearer token gửi trong header Authorization",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.sig")
        String accessToken,

        @Schema(description = "Hạn tuyệt đối của phiên (`session.ttl_hours` [CFG])",
                example = "2026-10-14T14:30:00Z")
        Instant expiresAt,

        @Schema(description = "Hạn của `accessToken` (`session.access_ttl_minutes` [CFG]); sau mốc này gọi "
                + "`POST /auth/refresh`",
                example = "2026-10-14T02:40:00Z")
        Instant accessTokenExpiresAt,

        @Schema(description = "Refresh token dùng một lần để lấy access token mới (ADR-0091)",
                example = "Zm9vYmFyLXJlZnJlc2gtdG9rZW4tZXhhbXBsZS0wMDAwMDA")
        String refreshToken,

        @Schema(description = "Tài khoản vừa đăng nhập")
        AccountSummary account,

        @Schema(description = "Hồ sơ khách còn chờ quyết định liên kết (BR-TK-19); luôn false với nhân viên",
                example = "false")
        boolean linkDecisionPending) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, expiresAt=" + expiresAt + ", accessTokenExpiresAt="
                + accessTokenExpiresAt + ", refreshToken=***, account=" + account
                + ", linkDecisionPending=" + linkDecisionPending + "]";
    }
}
