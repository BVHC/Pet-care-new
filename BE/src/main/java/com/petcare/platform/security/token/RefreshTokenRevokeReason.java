package com.petcare.platform.security.token;

import java.util.Set;

/**
 * Hằng số cho cột refresh_tokens.revoke_reason (VARCHAR(50) tự do). Dùng chung
 * ở cả nơi ghi (RefreshTokenService/TokenIssuanceFacadeImpl) lẫn nơi lọc
 * (RefreshTokenCleanupJob/ADR-0003) để tránh lệch chuỗi do gõ tay.
 */
public final class RefreshTokenRevokeReason {

    public static final String ROTATED = "ROTATED";
    public static final String LOGOUT = "LOGOUT";
    public static final String LOCK_ACCOUNT = "LOCK_ACCOUNT";
    public static final String DEACTIVATE_ACCOUNT = "DEACTIVATE_ACCOUNT";

    public static final Set<String> KNOWN_SECURITY_REASONS = Set.of(LOGOUT, LOCK_ACCOUNT, DEACTIVATE_ACCOUNT);

    private RefreshTokenRevokeReason() {
    }
}
