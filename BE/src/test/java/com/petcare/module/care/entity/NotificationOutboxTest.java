package com.petcare.module.care.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.care.api.NotificationApi.Channel;

/** Chuyển trạng thái của dòng outbox và việc xóa khóa nhạy cảm khi vào trạng thái cuối (docs/adr/0012). */
class NotificationOutboxTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    private static NotificationOutbox row() {
        return NotificationOutbox.pending(Channel.EMAIL, "OTP_REGISTER", "a@petcare.test", null,
                Map.of("ma_otp", "123456", "thoi_han_phut", 5, "MAT_KHAU_TAM", "x", "reset_token", "t",
                        "ten_khach", "An"), NOW);
    }

    @Test
    void sentRemovesSensitiveKeysAndKeepsTheRest() {
        NotificationOutbox row = row();

        row.markSent(NOW.plusSeconds(3));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getPayload()).containsOnlyKeys("thoi_han_phut", "ten_khach");
    }

    @Test
    void failedRemovesSensitiveKeys() {
        NotificationOutbox row = row();

        row.markFailed("boom");

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).isEqualTo("boom");
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getPayload()).doesNotContainKeys("ma_otp", "MAT_KHAU_TAM", "reset_token");
    }

    /** Còn lượt: vẫn PENDING, payload giữ nguyên để lần sau gửi được. */
    @Test
    void retryKeepsPayloadAndPendingStatus() {
        NotificationOutbox row = row();

        row.scheduleRetry(NOW.plusSeconds(60), "x".repeat(5000));

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).hasSize(NotificationOutbox.MAX_ERROR_LENGTH);
        assertThat(row.getPayload()).containsEntry("ma_otp", "123456");
    }

    @Test
    void sensitiveKeyMatchIgnoresCase() {
        assertThat(NotificationOutbox.isSensitiveKey("ma_OTP")).isTrue();
        assertThat(NotificationOutbox.isSensitiveKey("Mat_Khau_Tam")).isTrue();
        assertThat(NotificationOutbox.isSensitiveKey("link_url")).isFalse();
        assertThat(NotificationOutbox.isSensitiveKey("ten_khach")).isFalse();
    }
}
