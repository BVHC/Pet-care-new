package com.petcare.platform.security.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Session artifact hệ thống (JWT refresh token) — không phải entity nghiệp vụ nên
 * không extend platform.model.BaseEntity (không cần created_by/deleted_at). CÓ
 * {@code @Version} (khác giả định ban đầu) — phát hiện qua code review: {@code rotate()}
 * (refresh) và {@code doRevoke()}/{@code revokeByAccessTokenJti()} (logout) đều
 * read-modify-save cùng 1 row, 2 luồng nghiệp vụ độc lập chạy song song có thể lost-update
 * (revoked_at/revoke_reason/replaced_by của thread ghi sau đè mất field thread ghi trước
 * vừa set) — xem V11__refresh_token_version.sql, RefreshTokenService#rotate/doRevoke.
 * Xem V2__auth_session_tokens.sql.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshTokenEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "jti", nullable = false)
    private UUID jti;

    /**
     * JTI của access token được phát hành CÙNG LÚC với refresh token này (issue
     * hoặc rotate) — cho phép Logout tra ra đúng refresh token cần thu hồi chỉ từ
     * access token trong header Authorization, không phụ thuộc client có gửi kèm
     * raw refresh token trong body hay không (RULE-01-06).
     */
    @Column(name = "access_token_jti")
    private UUID accessTokenJti;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address")
    private String ipAddress;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public RefreshTokenEntity(UUID accountId, UUID jti, UUID accessTokenJti, String tokenHash, Instant issuedAt,
                               Instant expiresAt, String userAgent, String ipAddress) {
        this.accountId = accountId;
        this.jti = jti;
        this.accessTokenJti = accessTokenJti;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
