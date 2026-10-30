package com.petcare.module.inventory.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.jobs.stock-alert-digest} — ST08 cảnh báo tồn kho dưới ngưỡng, lô sắp / đã hết hạn (BR-KO-07, docs/adr/0087).
 * Số ngày "sắp hết hạn" là [CFG] {@code stock.expiry_warning_days}. Mỗi chi nhánh một transaction.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng (chi nhánh còn lại nhận ở lần chạy hôm sau)
 */
@Validated
@ConfigurationProperties("app.jobs.stock-alert-digest")
public record StockAlertDigestProperties(
        @NotBlank String cron,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
