package com.petcare.module.ai.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.chat-log-cleanup} (ST23, docs/adr/0130). Tham số vận hành, không phải [CFG]; hạn lưu nhật ký là [CFG]
 * {@code ai.log.retention_days} (BR-AI-24), đọc ở mỗi lượt. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số dòng xóa trong một transaction
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.chat-log-cleanup")
public record ChatLogCleanupProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
