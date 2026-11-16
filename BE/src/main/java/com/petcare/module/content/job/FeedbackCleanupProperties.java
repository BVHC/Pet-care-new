package com.petcare.module.content.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.feedback-cleanup} (ST24, docs/adr/0122, 0131). Tham số vận hành, không phải [CFG]; hạn lưu feedback là
 * [CFG] {@code feedback.retention_days} (BR-DG-05), đọc ở mỗi lượt. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số dòng xóa trong một transaction
 * @param batchGap   thời gian nghỉ sau mỗi lô đầy, để một đợt tồn đọng lớn không xóa liên tục làm chậm request; {@code 0} là không nghỉ
 * @param timeBudget thời gian tối đa một lượt (cả thời gian nghỉ); hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.feedback-cleanup")
public record FeedbackCleanupProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 0) Duration batchGap,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
