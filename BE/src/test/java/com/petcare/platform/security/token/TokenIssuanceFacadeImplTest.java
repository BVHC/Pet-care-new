package com.petcare.platform.security.token;

import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RULE-01-06 — Logout phải luôn cố thu hồi refresh token của phiên, kể cả khi client không
 * gửi kèm raw refresh token trong body (field optional, docs/api/auth-v1.md #LogoutRequest).
 */
@ExtendWith(MockitoExtension.class)
class TokenIssuanceFacadeImplTest {

    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;

    private TokenIssuanceFacadeImpl newFacade() {
        return new TokenIssuanceFacadeImpl(tokenProvider, refreshTokenService, tokenBlacklistService);
    }

    private static UserPrincipal principalWithUserId(UUID userId) {
        return UserPrincipal.builder().userId(userId).build();
    }

    @Test
    void logout_withRawRefreshToken_revokesByRawToken_notByAccessJti() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accessJti = UUID.randomUUID();
        UUID sameAccountUserId = UUID.randomUUID();
        when(tokenProvider.getJti("access-raw")).thenReturn(accessJti);
        when(tokenProvider.getExpiresAt("access-raw")).thenReturn(Instant.now().plusSeconds(60));
        when(tokenProvider.getTokenType("refresh-raw")).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        when(tokenProvider.parseToken("access-raw")).thenReturn(principalWithUserId(sameAccountUserId));
        when(tokenProvider.parseToken("refresh-raw")).thenReturn(principalWithUserId(sameAccountUserId));
        when(refreshTokenService.revoke("refresh-raw", RefreshTokenRevokeReason.LOGOUT)).thenReturn(true);

        boolean revoked = facade.logout("access-raw", "refresh-raw");

        assertThat(revoked).isTrue();
        verify(tokenBlacklistService).blacklist(eq(accessJti), any(), eq(RefreshTokenRevokeReason.LOGOUT));
        verify(refreshTokenService).revoke("refresh-raw", RefreshTokenRevokeReason.LOGOUT);
        verify(refreshTokenService, never()).revokeByAccessTokenJti(any(), any());
    }

    // RULE (bảo mật, phát hiện qua code review) — logout KHÔNG được cho phép actor A thu hồi
    // refresh token của tài khoản B chỉ vì biết được chuỗi thô của B (rò rỉ log/XSS/thiết bị
    // dùng chung). Phải verify chủ sở hữu refresh token khớp với access token đang xác thực actor.

    @Test
    void logout_refreshTokenBelongsToDifferentAccount_throwsAndDoesNotRevoke() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accessJti = UUID.randomUUID();
        when(tokenProvider.getJti("access-raw")).thenReturn(accessJti);
        when(tokenProvider.getExpiresAt("access-raw")).thenReturn(Instant.now().plusSeconds(60));
        when(tokenProvider.getTokenType("victim-refresh-raw")).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        when(tokenProvider.parseToken("access-raw")).thenReturn(principalWithUserId(UUID.randomUUID()));
        when(tokenProvider.parseToken("victim-refresh-raw")).thenReturn(principalWithUserId(UUID.randomUUID()));

        assertThatThrownBy(() -> facade.logout("access-raw", "victim-refresh-raw"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(refreshTokenService, never()).revoke(any(), any());
    }

    @Test
    void logout_refreshFieldIsActuallyAnAccessToken_throwsAndDoesNotRevoke() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accessJti = UUID.randomUUID();
        when(tokenProvider.getJti("access-raw")).thenReturn(accessJti);
        when(tokenProvider.getExpiresAt("access-raw")).thenReturn(Instant.now().plusSeconds(60));
        when(tokenProvider.getTokenType("not-a-refresh-token")).thenReturn(JwtTokenProvider.TOKEN_TYPE_ACCESS);

        assertThatThrownBy(() -> facade.logout("access-raw", "not-a-refresh-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(refreshTokenService, never()).revoke(any(), any());
    }

    @Test
    void logout_withoutRawRefreshToken_fallsBackToAccessJtiLookup_andRevokesLinkedSession() {
        // Đây là kịch bản trước đây bị bỏ sót hoàn toàn: client không gửi refreshToken ->
        // trước fix, refresh token của phiên vẫn "sống" sau khi logout.
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accessJti = UUID.randomUUID();
        when(tokenProvider.getJti("access-raw")).thenReturn(accessJti);
        when(tokenProvider.getExpiresAt("access-raw")).thenReturn(Instant.now().plusSeconds(60));
        when(refreshTokenService.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT)).thenReturn(true);

        boolean revoked = facade.logout("access-raw", null);

        assertThat(revoked).isTrue();
        verify(tokenBlacklistService).blacklist(eq(accessJti), any(), eq(RefreshTokenRevokeReason.LOGOUT));
        verify(refreshTokenService).revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT);
        verify(refreshTokenService, never()).revoke(any(), any());
    }

    @Test
    void logout_withoutRawRefreshToken_noLinkedSessionFound_returnsFalse_stillBlacklistsAccess() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accessJti = UUID.randomUUID();
        when(tokenProvider.getJti("access-raw")).thenReturn(accessJti);
        when(tokenProvider.getExpiresAt("access-raw")).thenReturn(Instant.now().plusSeconds(60));
        when(refreshTokenService.revokeByAccessTokenJti(accessJti, RefreshTokenRevokeReason.LOGOUT)).thenReturn(false);

        boolean revoked = facade.logout("access-raw", null);

        assertThat(revoked).isFalse();
        verify(tokenBlacklistService).blacklist(eq(accessJti), any(), eq(RefreshTokenRevokeReason.LOGOUT));
    }

    @Test
    void logout_noAccessTokenAndNoRefreshToken_returnsFalse_doesNothing() {
        TokenIssuanceFacadeImpl facade = newFacade();

        boolean revoked = facade.logout(null, null);

        assertThat(revoked).isFalse();
        verify(tokenBlacklistService, never()).blacklist(any(), any(), any());
        verify(refreshTokenService, never()).revoke(any(), any());
        verify(refreshTokenService, never()).revokeByAccessTokenJti(any(), any());
    }

    // RULE-02-04/07 — LockAccount/DeactivateAccount phải blacklist access token của MỌI phiên
    // đang active, không chỉ revoke refresh token trong DB (bug trước đây: access token cũ vẫn
    // dùng được tới hết TTL sau khi tài khoản đã bị khóa).

    @Test
    void revokeAllSessions_blacklistsAccessTokenOfEveryActiveSession() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accountId = UUID.randomUUID();
        UUID accessJti1 = UUID.randomUUID();
        UUID accessJti2 = UUID.randomUUID();
        when(tokenProvider.getAccessTokenTtlMs()).thenReturn(900_000L);
        when(refreshTokenService.revokeAllForAccount(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT))
                .thenReturn(List.of(accessJti1, accessJti2));

        facade.revokeAllSessions(accountId, RefreshTokenRevokeReason.LOCK_ACCOUNT);

        verify(tokenBlacklistService).blacklist(eq(accessJti1), any(), eq(RefreshTokenRevokeReason.LOCK_ACCOUNT));
        verify(tokenBlacklistService).blacklist(eq(accessJti2), any(), eq(RefreshTokenRevokeReason.LOCK_ACCOUNT));
    }

    @Test
    void revokeAllSessions_noActiveSessions_doesNotCallBlacklist() {
        TokenIssuanceFacadeImpl facade = newFacade();
        UUID accountId = UUID.randomUUID();
        when(refreshTokenService.revokeAllForAccount(accountId, RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT))
                .thenReturn(List.of());

        facade.revokeAllSessions(accountId, RefreshTokenRevokeReason.DEACTIVATE_ACCOUNT);

        verify(tokenBlacklistService, never()).blacklist(any(), any(), any());
    }
}
