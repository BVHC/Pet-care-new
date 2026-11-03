package com.petcare.module.identity.entity;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code sessions} (erd §1, PART của Account). Mỗi access token gắn với đúng một phiên: {@code id} là claim
 * {@code sid}, {@code tokenHash} = SHA-256 của claim {@code jti} (docs/adr/0003); không lưu token gốc. Hủy phiên chỉ
 * đặt {@code revokedAt}; dòng hết hạn quá thời gian lưu bị job xóa (docs/adr/0008). {@code expiresAt} chốt lúc tạo
 * theo [CFG] (BR-QT-13). {@code accessExpiresAt}: hạn của access token hiện hành (docs/adr/0091); nhân viên online ⇔ có
 * phiên chưa hủy với hạn này còn tới, nên chỉ đổi qua câu UPDATE có điều kiện ({@code SessionRepository.rotateAccess}),
 * không qua entity. {@code null} với phiên mở trước V27.
 */
@Getter
@Entity
@Table(name = "sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Session extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "access_expires_at", updatable = false)
    private Instant accessExpiresAt;

    public Session(Long accountId, String tokenHash, String ipAddress, String userAgent, Instant expiresAt,
            Instant accessExpiresAt) {
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
        this.accessExpiresAt = accessExpiresAt;
    }
}
