package com.petcare.module.auth.dto;

/**
 * docs/api/auth-v1.md #LogoutRequest — mọi field tuỳ chọn (RULE-01-06:
 * revoke theo refresh token nếu có, luôn blacklist access token từ header
 * Authorization). Body có thể rỗng/null (xem AuthController#logout).
 */
public record LogoutRequest(String refreshToken) {
}
