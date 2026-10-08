package com.petcare.module.care.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.module.care.api.NotificationApi.Channel;
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
 * Bảng {@code notification_outbox} (erd §11, LOG). Ghi trong transaction nghiệp vụ, worker ST20 gửi và thử lại sau
 * (convention 07 §7.2), nên rollback nghiệp vụ thì không có thông báo nào. {@code template_code} có FK tới
 * {@code notification_templates}: mẫu phải được seed trước lần ghi đầu tiên (06 §8 Q4).
 * <p>
 * Bảng LOG nhưng worker ST20 cập nhật {@code status}, {@code attempts}, {@code next_attempt_at}, {@code last_error},
 * {@code sent_at} và xóa khóa nhạy cảm khỏi {@code payload} khi dòng vào trạng thái cuối (docs/adr/0012, erd §13
 * mục 11). Các cột còn lại ghi một lần.
 */
@Getter
@Entity
@Table(name = "notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends CreatedAtEntity {

    /** Từ trong tên khóa payload bị xóa khi dòng vào {@code SENT}/{@code FAILED} (mã OTP, mật khẩu tạm…). */
    static final Set<String> SENSITIVE_KEY_WORDS = Set.of("otp", "mat_khau", "password", "token", "secret");

    /** {@code last_error} chỉ giữ phần đầu, đủ để chẩn đoán. */
    static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private Channel channel;

    @Column(name = "template_code", nullable = false, updatable = false)
    private String templateCode;

    @Column(name = "recipient_email", updatable = false)
    private String recipientEmail;

    @Column(name = "recipient_account_id", updatable = false)
    private Long recipientAccountId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Dòng mới chờ gửi ngay ({@code next_attempt_at = now}). */
    public static NotificationOutbox pending(Channel channel, String templateCode, String recipientEmail,
            Long recipientAccountId, Map<String, Object> payload, Instant now) {
        NotificationOutbox row = new NotificationOutbox();
        row.channel = channel;
        row.templateCode = templateCode;
        row.recipientEmail = recipientEmail;
        row.recipientAccountId = recipientAccountId;
        row.payload = new HashMap<>(payload);
        row.status = OutboxStatus.PENDING;
        row.nextAttemptAt = now;
        return row;
    }

    /** Gửi thành công: {@code SENT}, tính một lần gửi, xóa khóa nhạy cảm. */
    public void markSent(Instant now) {
        attempts++;
        status = OutboxStatus.SENT;
        sentAt = now;
        scrubSensitivePayload();
    }

    /** Lỗi tạm thời, còn lượt: giữ {@code PENDING}, lùi lần gửi kế tiếp. */
    public void scheduleRetry(Instant nextAttempt, String error) {
        attempts++;
        nextAttemptAt = nextAttempt;
        lastError = truncate(error);
    }

    /** Lỗi vĩnh viễn hoặc hết lượt: {@code FAILED}, xóa khóa nhạy cảm. */
    public void markFailed(String error) {
        attempts++;
        status = OutboxStatus.FAILED;
        lastError = truncate(error);
        scrubSensitivePayload();
    }

    private void scrubSensitivePayload() {
        Map<String, Object> kept = new HashMap<>();
        payload.forEach((key, value) -> {
            if (!isSensitiveKey(key)) {
                kept.put(key, value);
            }
        });
        payload = kept;
    }

    static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_WORDS.stream().anyMatch(lower::contains);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
