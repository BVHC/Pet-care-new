package com.petcare.module.sales.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.cashier-shift-auto-close} — ST13 tự chốt ca thu ngân (docs/adr/0064). Tham số vận hành, không phải
 * [CFG]: số phút cộng thêm sau giờ đóng cửa là [CFG] {@code cashier_shift.auto_close_grace_minutes}. Sai giá trị thì app
 * dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số id đọc một lần (mỗi ca vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.cashier-shift-auto-close")
public record CashierShiftAutoCloseProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
