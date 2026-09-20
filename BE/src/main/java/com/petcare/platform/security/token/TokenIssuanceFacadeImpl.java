package com.petcare.platform.security.token;

import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
        UUID accessJti = UUID.randomUUID();
        String accessToken = tokenProvider.generateAccessToken(principal, accessJti);
        String refreshToken = tokenProvider.generateRefreshToken(principal);

        refreshTokenService.issue(
                principal.getAccountId(),
                refreshToken,
                tokenProvider.getJti(refreshToken),
                accessJti,
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

        UUID newAccessJti = UUID.randomUUID();
        String newAccessToken = tokenProvider.generateAccessToken(refreshedPrincipal, newAccessJti);
        String newRefreshToken = tokenProvider.generateRefreshToken(refreshedPrincipal);

        refreshTokenService.rotate(
                rawRefreshToken,
                newRefreshToken,
                tokenProvider.getJti(newRefreshToken),
                newAccessJti,
                tokenProvider.getExpiresAt(newRefreshToken));

        return toPair(newAccessToken, newRefreshToken);
    }

    @Override
    @Transactional
    public boolean logout(String rawAccessToken, String rawRefreshToken) {
        UUID accessJti = null;
        if (rawAccessToken != null) {
            accessJti = tokenProvider.getJti(rawAccessToken);
            tokenBlacklistService.blacklist(accessJti, tokenProvider.getExpiresAt(rawAccessToken), RefreshTokenRevokeReason.LOGOUT);
        }
        if (rawRefreshToken != null) {
            // Chống thu hồi refresh token của tài khoản KHÁC: revoke() tra theo hash tuyệt đối,
            // không tự lọc theo actor — nếu không check ở đây, actor A biết được raw refresh
            // token của B (rò rỉ log/XSS/thiết bị dùng chung) có thể tự đăng xuất B bằng access
            // token hợp lệ của chính A. Mirror đúng guard đã có ở refreshTokens() (subject JWT,
            // không dùng accountId vì refresh token không mang claim này — xem generateRefreshToken).
            if (!JwtTokenProvider.TOKEN_TYPE_REFRESH.equals(tokenProvider.getTokenType(rawRefreshToken))) {
                throw new IllegalArgumentException("Provided token is not a refresh token");
            }
            // rawAccessToken luôn non-null qua controller thật (SecurityConfig bắt buộc Bearer cho
            // /api/auth/logout, JwtAuthenticationFilter chặn request trước khi tới đây) — check
            // tường minh chỉ để không NPE nếu caller khác gọi facade trực tiếp thiếu access token.
            if (rawAccessToken == null) {
                throw new IllegalArgumentException("Access token is required to revoke a refresh token");
            }
            UUID accessSubjectId = tokenProvider.parseToken(rawAccessToken).getUserId();
            UUID refreshSubjectId = tokenProvider.parseToken(rawRefreshToken).getUserId();
            if (!accessSubjectId.equals(refreshSubjectId)) {
                throw new IllegalArgumentException("Refresh token does not belong to the authenticated account");
            }
            return refreshTokenService.revoke(rawRefreshToken, RefreshTokenRevokeReason.LOGOUT);
        }
        // RULE-01-06 — client không gửi kèm refresh token (field optional, docs/api/auth-v1.md
        // #LogoutRequest): tự tra refresh token của phiên qua JTI access token thay vì bỏ qua
        // việc thu hồi — nếu không, phiên vẫn "sống" và refresh token cũ vẫn đổi được access
        // token mới sau khi client tưởng đã đăng xuất thành công.
        if (accessJti != null) {
            return refreshTokenService.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT);
        }
        return false;
    }

    @Override
    @Transactional
    public void revokeAllSessions(UUID accountId, String reason) {
        // RULE-02-04/07 — trước đây chỉ revoke refresh token (DB), access token JWT đang cầm
        // vẫn dùng được tới hết TTL (15 phút, JwtAuthenticationFilter xác thực stateless, không
        // query DB account.status mỗi request) vì không được đưa vào blacklist. Không biết chính
        // xác thời điểm access token hết hạn (chỉ lưu accessTokenJti, không lưu expiry riêng) nên
        // dùng now()+accessTokenTtlMs làm cận trên an toàn cho TTL blacklist — không bao giờ thiếu,
        // tối đa dư vài phút lưu trên Redis so với thời điểm token thật hết hạn.
        List<UUID> accessTokenJtis = refreshTokenService.revokeAllForAccount(accountId, reason);
        Instant blacklistUntil = Instant.now().plusMillis(tokenProvider.getAccessTokenTtlMs());
        accessTokenJtis.forEach(jti -> tokenBlacklistService.blacklist(jti, blacklistUntil, reason));
    }

    private IssuedTokenPair toPair(String accessToken, String refreshToken) {
        long expiresIn = TimeUnit.MILLISECONDS.toSeconds(tokenProvider.getAccessTokenTtlMs());
        return new IssuedTokenPair(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
