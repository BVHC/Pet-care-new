package com.petcare.module.shop.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.online-order-auto-confirm} — ST12 tự xác nhận đã nhận (docs/adr/0146). Tham số vận hành, không phải
 * [CFG] (số ngày là [CFG] {@code shop.auto_confirm_days}, đọc lúc chạy). Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số đơn đọc một lần (mỗi đơn vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.online-order-auto-confirm")
public record OnlineOrderAutoConfirmProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
