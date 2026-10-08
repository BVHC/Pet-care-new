package com.petcare.module.identity.dto;

import java.time.Instant;

/**
 * Kết quả đăng nhập (identity-v1 {@code LoginResponse}). {@code expiresAt}: hạn tuyệt đối của phiên
 * ({@code session.ttl_hours} [CFG], không gia hạn — docs/adr/0003). {@code linkDecisionPending}: hồ sơ khách còn cờ chờ
 * quyết định liên kết (BR-TK-19) — FE nhắc lại lời đề nghị; luôn {@code false} với nhân viên.
 */
public record LoginResponse(String accessToken, Instant expiresAt, AccountSummary account,
                            boolean linkDecisionPending) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, expiresAt=" + expiresAt + ", account=" + account
                + ", linkDecisionPending=" + linkDecisionPending + "]";
    }
}
