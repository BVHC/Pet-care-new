package com.petcare.module.boarding.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.boarding-no-show} — ST05 cho lưu trú (docs/adr/0065). Tham số vận hành, không phải [CFG]: mốc quá hạn
 * là giờ đóng cửa của ngày nhận. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số đặt chỗ đọc một lần (mỗi đặt chỗ vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.boarding-no-show")
public record BoardingNoShowProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
