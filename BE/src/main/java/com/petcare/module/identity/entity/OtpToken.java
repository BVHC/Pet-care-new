package com.petcare.module.identity.entity;

import java.time.Instant;

import com.petcare.platform.model.CreatedAtEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code otp_tokens} (erd §1, LOG). Chỉ lưu {@code code_hash}, không lưu mã gốc (docs/adr/0009).
 * {@code expiresAt} chốt lúc sinh theo [CFG] (BR-QT-13). Là LOG nhưng được cập nhật {@code failed_attempts},
 * {@code consumed_at}, {@code invalidated_at} (erd L146).
 */
@Getter
@Entity
@Table(name = "otp_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OtpToken extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    private OtpPurpose purpose;

    @Column(name = "target_email", nullable = false, updatable = false)
    private String targetEmail;

    /** Chỉ có với {@code LINK_PROFILE} (BR-TK-19). */
    @Column(name = "customer_id", updatable = false)
    private Long customerId;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    public OtpToken(Long accountId, OtpPurpose purpose, String targetEmail, String codeHash, Instant expiresAt) {
        this.accountId = accountId;
        this.purpose = purpose;
        this.targetEmail = targetEmail;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    /** BR-TK-05: mã hết hiệu lực từ đúng thời điểm {@code expires_at}. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * BR-TK-06: thêm một lần nhập sai; chạm {@code maxFailedAttempts} thì hủy mã ngay. Trả {@code true} khi mã vừa
     * bị hủy.
     */
    public boolean recordFailedAttempt(Instant now, int maxFailedAttempts) {
        failedAttempts++;
        if (failedAttempts >= maxFailedAttempts) {
            invalidatedAt = now;
            return true;
        }
        return false;
    }

    /** BR-TK-05: mã chỉ dùng được một lần. */
    public void consume(Instant now) {
        consumedAt = now;
    }
}
