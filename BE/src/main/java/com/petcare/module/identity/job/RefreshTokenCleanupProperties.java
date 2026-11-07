package com.petcare.module.identity.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.refresh-token-cleanup} (docs/adr/0114, trả D054). Tham số vận hành, không phải [CFG]: không có trong
 * 02 và ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * <p>Không có bất biến nào buộc {@code retentionDays}: {@code refresh_tokens.expires_at} luôn bằng
 * {@code sessions.expires_at}, nên dòng bị xóa thuộc phiên đã hết hạn và không còn dùng được cho phát hiện dùng lại
 * token (docs/adr/0091). {@code @Positive} chỉ giữ một khoảng đệm tối thiểu 1 ngày để điều tra.
 *
 * @param cron          biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param retentionDays số ngày giữ refresh token sau {@code expires_at}
 * @param batchSize     số dòng xóa trong một transaction
 * @param timeBudget    thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.refresh-token-cleanup")
public record RefreshTokenCleanupProperties(
        @NotBlank String cron,
        @Positive int retentionDays,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
