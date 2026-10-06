package com.petcare.module.identity.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.session-cleanup} (docs/adr/0008). Tham số vận hành, không phải [CFG]: không có trong 02 và ADMIN không
 * sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron          biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param retentionDays số ngày giữ phiên sau {@code expires_at}
 * @param batchSize     số dòng xóa trong một transaction
 */
@Validated
@ConfigurationProperties("app.jobs.session-cleanup")
public record SessionCleanupProperties(@NotBlank String cron, @Positive int retentionDays, @Positive int batchSize) {
}
