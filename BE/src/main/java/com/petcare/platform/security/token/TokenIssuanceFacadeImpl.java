package com.petcare.platform.security.token;

import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class TokenIssuanceFacadeImpl implements TokenIssuanceFacade {

    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    @Transactional
    public IssuedTokenPair issueTokens(UserPrincipal principal, String userAgent, String ipAddress) {
        String accessToken = tokenProvider.generateAccessToken(principal);
        String refreshToken = tokenProvider.generateRefreshToken(principal);

        refreshTokenService.issue(
                principal.getAccountId(),
                refreshToken,
                tokenProvider.getJti(refreshToken),
                tokenProvider.getExpiresAt(refreshToken),
                userAgent,
                ipAddress);

        return toPair(accessToken, refreshToken);
    }

    @Override
    @Transactional
    public IssuedTokenPair refreshTokens(String rawRefreshToken, UserPrincipal refreshedPrincipal,
                                          String userAgent, String ipAddress) {
        if (!JwtTokenProvider.TOKEN_TYPE_REFRESH.equals(tokenProvider.getTokenType(rawRefreshToken))) {
            throw new IllegalArgumentException("Provided token is not a refresh token");
        }
        UUID subjectUserId = tokenProvider.parseToken(rawRefreshToken).getUserId();
        if (!subjectUserId.equals(refreshedPrincipal.getUserId())) {
            throw new IllegalArgumentException("Refresh token does not belong to the given principal");
        }

        String newAccessToken = tokenProvider.generateAccessToken(refreshedPrincipal);
        String newRefreshToken = tokenProvider.generateRefreshToken(refreshedPrincipal);

        refreshTokenService.rotate(
                rawRefreshToken,
                newRefreshToken,
                tokenProvider.getJti(newRefreshToken),
                tokenProvider.getExpiresAt(newRefreshToken));

        return toPair(newAccessToken, newRefreshToken);
    }

    @Override
    @Transactional
    public void logout(String rawAccessToken, String rawRefreshToken) {
        if (rawAccessToken != null) {
            UUID jti = tokenProvider.getJti(rawAccessToken);
            tokenBlacklistService.blacklist(jti, tokenProvider.getExpiresAt(rawAccessToken), RefreshTokenRevokeReason.LOGOUT);
        }
        if (rawRefreshToken != null) {
            refreshTokenService.revoke(rawRefreshToken, RefreshTokenRevokeReason.LOGOUT);
        }
    }

    @Override
    @Transactional
    public void revokeAllSessions(UUID accountId, String reason) {
        refreshTokenService.revokeAllForAccount(accountId, reason);
    }

    private IssuedTokenPair toPair(String accessToken, String refreshToken) {
        long expiresIn = TimeUnit.MILLISECONDS.toSeconds(tokenProvider.getAccessTokenTtlMs());
        return new IssuedTokenPair(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
