package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.RefreshToken;
import com.petcare.module.identity.entity.Session;
import com.petcare.module.identity.repository.RefreshTokenRepository;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.security.JwtTokenService;

/**
 * Mở và hủy phiên đăng nhập (docs/adr/0003, docs/adr/0091). Mọi hàm chạy trong transaction của use case gọi tới
 * (đăng nhập, đăng xuất, đổi/đặt lại mật khẩu, khóa, vô hiệu hóa) nên đặt {@code MANDATORY}. Hủy phiên chỉ đặt
 * {@code revoked_at}; dòng hết hạn quá thời gian lưu do {@link SessionCleanupService} xóa (docs/adr/0008).
 */
@Service
public class SessionService {

    static final int IP_MAX_LENGTH = 45;
    static final int USER_AGENT_MAX_LENGTH = 500;

    /**
     * Kết quả mở phiên. {@code expiresAt}: hạn tuyệt đối của phiên ({@code session.ttl_hours}); {@code accessExpiresAt}:
     * hạn của {@code accessToken} ({@code session.access_ttl_minutes}, không vượt {@code expiresAt}).
     */
    public record OpenedSession(Long sessionId, String accessToken, Instant expiresAt, Instant accessExpiresAt,
            String refreshToken) {

        @Override
        public String toString() {
            return "OpenedSession[sessionId=" + sessionId + ", accessToken=***, expiresAt=" + expiresAt
                    + ", accessExpiresAt=" + accessExpiresAt + ", refreshToken=***]";
        }
    }

    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final SystemConfigApi configs;
    private final JwtTokenService tokens;
    private final Clock clock;

    public SessionService(SessionRepository sessions, RefreshTokenRepository refreshTokens, SystemConfigApi configs,
            JwtTokenService tokens, Clock clock) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.configs = configs;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Tạo phiên, refresh token đầu tiên rồi ký access token cho phiên đó. Hạn phiên = bây giờ +
     * {@code session.ttl_hours} [CFG], chốt vào {@code expires_at} (BR-QT-13); hạn access token =
     * {@link #accessExpiry}; cả hai làm tròn xuống giây để khớp {@code exp} của JWT. Refresh token hết hạn cùng phiên.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OpenedSession open(Long accountId, String ipAddress, String userAgent) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(Duration.ofHours(configs.getInt(ConfigKey.SESSION_TTL_HOURS)));
        Instant accessExpiresAt = accessExpiry(now, expiresAt);
        String jti = JtiHasher.newJti();
        Session session = sessions.save(new Session(accountId, JtiHasher.hash(jti),
                truncate(ipAddress, IP_MAX_LENGTH), truncate(userAgent, USER_AGENT_MAX_LENGTH), expiresAt,
                accessExpiresAt));
        String refreshToken = JtiHasher.newRefreshToken();
        refreshTokens.save(new RefreshToken(session.getId(), JtiHasher.hash(refreshToken), expiresAt));
        String token = tokens.issue(accountId, session.getId(), jti, now, accessExpiresAt);
        return new OpenedSession(session.getId(), token, expiresAt, accessExpiresAt, refreshToken);
    }

    /**
     * Hạn access token = bây giờ + {@code session.access_ttl_minutes} [CFG], không vượt hạn phiên (docs/adr/0091).
     * Cũng là mốc "online" của nhân viên (BR-TN-06).
     */
    Instant accessExpiry(Instant now, Instant sessionExpiresAt) {
        Instant byTtl = now.plus(Duration.ofMinutes(configs.getInt(ConfigKey.SESSION_ACCESS_TTL_MINUTES)));
        return byTtl.isBefore(sessionExpiresAt) ? byTtl : sessionExpiresAt;
    }

    /** Đăng xuất phiên hiện tại. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(Long sessionId) {
        sessions.revokeById(sessionId, Instant.now(clock));
    }

    /** Khóa, vô hiệu hóa, đặt lại mật khẩu: hủy mọi phiên (BR-TK-11, 13, BR-QT-09). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(Long accountId) {
        sessions.revokeAllByAccount(accountId, Instant.now(clock));
    }

    /** Đổi mật khẩu: hủy mọi phiên khác, giữ phiên đang dùng (BR-TK-14). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeOthers(Long accountId, Long currentSessionId) {
        sessions.revokeAllByAccountExcept(accountId, currentSessionId, Instant.now(clock));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
