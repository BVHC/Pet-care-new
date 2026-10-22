package com.petcare.module.sales.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.jobs.pending-order-digest} — ST13 liệt kê Order {@code PENDING} quá hạn cho quản lý cuối ngày
 * (docs/adr/0064). Ngưỡng "quá hạn" là [CFG] {@code order.pending_alert_days}, cùng bộ lọc {@code pendingOverdue}
 * của danh sách Order. Mỗi chi nhánh một transaction.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng (chi nhánh còn lại nhận ở lượt sau nếu chạy lại)
 */
@Validated
@ConfigurationProperties("app.jobs.pending-order-digest")
public record PendingOrderDigestProperties(
        @NotBlank String cron,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
