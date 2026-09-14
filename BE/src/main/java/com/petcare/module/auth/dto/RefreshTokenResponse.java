package com.petcare.module.auth.dto;

/** ADR-0001 — cặp token mới sau khi rotate refresh token cũ. */
public record RefreshTokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
