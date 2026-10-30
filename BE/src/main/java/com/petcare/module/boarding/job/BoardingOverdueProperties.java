package com.petcare.module.boarding.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.boarding-overdue} — ST15 quá hạn đón (docs/adr/0086). Tham số vận hành, không phải [CFG]: mốc quá hạn
 * là giờ trả quy định ({@code boarding.checkout_time}) của ngày trả dự kiến. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số đặt chỗ đọc một lần (mỗi đặt chỗ vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt (cả hai lượt quét); hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.boarding-overdue")
public record BoardingOverdueProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
