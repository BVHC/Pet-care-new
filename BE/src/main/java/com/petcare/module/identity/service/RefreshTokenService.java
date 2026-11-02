package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.RefreshTokenResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.RefreshToken;
import com.petcare.module.identity.exception.InvalidRefreshTokenException;
import com.petcare.module.identity.repository.RefreshTokenRepository;
import com.petcare.module.identity.repository.SessionAuthView;
import com.petcare.module.identity.repository.SessionRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.security.JwtTokenService;

import lombok.extern.slf4j.Slf4j;

/**
 * UC03 — làm mới access token ({@code POST /api/auth/refresh}, docs/adr/0091). Path public: xác thực bằng refresh
 * token, không bằng bearer. Một transaction, không BCrypt (chỉ SHA-256) nên không cần facade. Thứ tự (khóa → đọc →
 * guard → ghi → ký), mọi nhánh từ chối ném {@link InvalidRefreshTokenException} (401 chung) <b>trước</b> mọi lệnh ghi:
 * <ol>
 *   <li>khóa dòng {@code refresh_tokens} theo băm ({@code FOR NO KEY UPDATE}) — hai refresh cùng token xếp hàng ở đây;</li>
 *   <li>{@code now} lấy sau khóa; đọc phiên và tài khoản ({@link SessionRepository#findAuthView});</li>
 *   <li><b>token đã dùng</b> ⇒ token bị lộ hoặc client dùng lại token cũ: hủy phiên + audit
 *       {@link IdentityAuditActions#REFRESH_TOKEN_REUSED}, rồi 401 (hai lệnh ghi này commit nhờ
 *       {@code noRollbackFor}, cùng khuôn {@code InvalidCredentialsException}). Phiên đã hủy / hết hạn thì chỉ 401;</li>
 *   <li>token hết hạn, phiên hủy / hết hạn, tài khoản không {@code ACTIVE} hoặc bị khóa ⇒ 401;</li>
 *   <li>ghi: {@link SessionRepository#rotateAccess} (jti mới + hạn access mới, phải trúng 1 dòng) → token cũ
 *       {@code used_at} → INSERT token mới; rồi ký access token.</li>
 * </ol>
 * Khóa: {@code refresh_tokens → sessions}, không bao giờ {@code accounts}, nên không vòng với {@code accounts → sessions}
 * của khóa / đăng xuất / đổi mật khẩu (docs/adr/0021); khóa tài khoản hủy phiên cùng transaction nên một refresh chạy
 * chéo chỉ cấp được token mà request kế tiếp của nó bị {@code SessionAuthenticationService} từ chối. Cố ý không có
 * "cửa sổ ân hạn" cho refresh song song: lượt sau thấy {@code used_at} đã đặt và bị coi là dùng lại.
 */
@Slf4j
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokens;
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final JwtTokenService tokens;
    private final AuditRecorder audit;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository refreshTokens, SessionRepository sessions,
            SessionService sessionService, JwtTokenService tokens, AuditRecorder audit, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RefreshTokenResponse refresh(String rawRefreshToken) {
        RefreshToken current = refreshTokens.findByTokenHashForUpdate(JtiHasher.hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        SessionAuthView session = sessions.findAuthView(current.getSessionId())
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.isUsed()) {
            if (session.revokedAt() == null && session.expiresAt().isAfter(now)) {
                revokeReusedSession(session, now);
            }
            throw new InvalidRefreshTokenException();
        }
        if (!current.getExpiresAt().isAfter(now) || session.revokedAt() != null || !session.expiresAt().isAfter(now)
                || session.status() != AccountStatus.ACTIVE || session.locked()) {
            throw new InvalidRefreshTokenException();
        }

        Instant accessExpiresAt = sessionService.accessExpiry(now, session.expiresAt());
        String jti = JtiHasher.newJti();
        if (sessions.rotateAccess(session.sessionId(), JtiHasher.hash(jti), accessExpiresAt, now) != 1) {
            throw new InvalidRefreshTokenException();   // phiên vừa bị hủy / hết hạn giữa đọc và ghi; chưa ghi gì khác
        }
        if (refreshTokens.markUsed(current.getId(), now) != 1) {
            throw new IllegalStateException("Refresh token " + current.getId() + " changed under its own row lock");
        }
        String nextRefreshToken = JtiHasher.newRefreshToken();
        refreshTokens.saveAndFlush(new RefreshToken(session.sessionId(), JtiHasher.hash(nextRefreshToken),
                session.expiresAt()));
        String accessToken = tokens.issue(session.accountId(), session.sessionId(), jti, now, accessExpiresAt);
        return new RefreshTokenResponse(accessToken, accessExpiresAt, nextRefreshToken, session.expiresAt());
    }

    private void revokeReusedSession(SessionAuthView session, Instant now) {
        sessions.revokeById(session.sessionId(), now);
        audit.record(AuditEntry.of(IdentityAuditActions.REFRESH_TOKEN_REUSED)
                .entity("sessions", session.sessionId())
                .actor(session.accountId(), session.email())
                .after(Map.of("sessionRevoked", true)));
        log.warn("REFRESH_TOKEN_REUSED accountId={} sessionId={}", session.accountId(), session.sessionId());
    }
}
