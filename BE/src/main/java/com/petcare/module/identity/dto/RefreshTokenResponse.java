package com.petcare.module.identity.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả {@code POST /api/auth/refresh} (identity-v1 {@code RefreshResponse}, docs/adr/0091). Access token cũ và
 * refresh token vừa gửi hết hiệu lực ngay. {@code expiresAt}: hạn tuyệt đối của phiên, không đổi.
 */
public record RefreshTokenResponse(
        @Schema(description = "Bearer token mới của cùng phiên; token cũ hết hiệu lực ngay",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.sig")
        String accessToken,

        @Schema(description = "Hạn của `accessToken` mới, không vượt `expiresAt`", example = "2026-10-14T02:50:00Z")
        Instant accessTokenExpiresAt,

        @Schema(description = "Refresh token mới; token vừa gửi không dùng lại được",
                example = "bmV3LXJlZnJlc2gtdG9rZW4tZXhhbXBsZS0wMDAwMDAwMDA")
        String refreshToken,

        @Schema(description = "Hạn tuyệt đối của phiên (không đổi)", example = "2026-10-14T14:30:00Z")
        Instant expiresAt) {

    @Override
    public String toString() {
        return "RefreshTokenResponse[accessToken=***, accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshToken=***, expiresAt=" + expiresAt + "]";
    }
}
