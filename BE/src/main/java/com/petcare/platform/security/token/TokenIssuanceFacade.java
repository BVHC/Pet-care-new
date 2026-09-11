package com.petcare.platform.security.token;

import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

/**
 * Contract hợp nhất JwtTokenProvider + RefreshTokenService + TokenBlacklistService
 * cho vòng đời session — module Auth (Module 01, chưa triển khai) sẽ gọi các
 * method này từ Login/Logout/RefreshToken/LockAccount/DeactivateAccount thay vì
 * thao tác trực tiếp 3 lớp bên dưới.
 * <p>
 * {@code refreshTokens} nhận sẵn {@code refreshedPrincipal} từ caller thay vì tự
 * tra cứu Account/User — hạ tầng này không phụ thuộc Account/User entity (chưa
 * tồn tại). Trách nhiệm lấy claim mới nhất (role/scope/accountStatus) thuộc về
 * module Auth khi nó được xây.
 */
public interface TokenIssuanceFacade {

    IssuedTokenPair issueTokens(UserPrincipal principal, String userAgent, String ipAddress);

    IssuedTokenPair refreshTokens(String rawRefreshToken, UserPrincipal refreshedPrincipal,
                                   String userAgent, String ipAddress);

    /** Blacklist access token jti (Redis) + revoke refresh token (DB) — RULE-01-06. */
    void logout(String rawAccessToken, String rawRefreshToken);

    /** Revoke toàn bộ refresh token đang active của account — RULE-02-04/07. */
    void revokeAllSessions(UUID accountId, String reason);
}
