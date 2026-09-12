package com.petcare.platform.security.token;

/**
 * Kết quả issue/refresh token — field name khớp FE (FE/src/shared/types/index.ts
 * AuthTokens: accessToken/refreshToken/tokenType/expiresIn) để module Auth tương
 * lai map thẳng khi implement Login/RefreshToken endpoint.
 */
public record IssuedTokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
