package com.petcare.module.care.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.notification-outbox-cleanup} (docs/adr/0016). Tham số vận hành, không phải [CFG]: không có trong 02 và
 * ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron                biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param sentRetentionDays   số ngày giữ dòng {@code SENT}, tính từ {@code created_at}
 * @param failedRetentionDays số ngày giữ dòng {@code FAILED}, tính từ {@code created_at} (giữ lâu hơn để điều tra)
 * @param batchSize           số dòng xóa trong một transaction
 */
@Validated
@ConfigurationProperties("app.jobs.notification-outbox-cleanup")
public record NotificationOutboxCleanupProperties(@NotBlank String cron, @Positive int sentRetentionDays,
        @Positive int failedRetentionDays, @Positive int batchSize) {
}
