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
 * Bảng {@code refresh_tokens} (erd §1, PART của {@link Session}; docs/adr/0091). Mỗi phiên có đúng một refresh token
 * còn dùng được ({@code used_at IS NULL}); mỗi lần refresh đánh dấu token cũ đã dùng rồi tạo token mới, nên dùng lại
 * token đã dùng nghĩa là token bị lộ và phiên bị hủy. Chỉ lưu SHA-256 của chuỗi ngẫu nhiên. {@code expiresAt} chốt
 * bằng hạn phiên lúc tạo (BR-QT-13): hạn phiên là trần tuyệt đối. {@code used_at} chỉ đổi qua
 * {@code RefreshTokenRepository.markUsed}, không qua entity.
 */
@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at", updatable = false)
    private Instant usedAt;

    public RefreshToken(Long sessionId, String tokenHash, Instant expiresAt) {
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }
}
