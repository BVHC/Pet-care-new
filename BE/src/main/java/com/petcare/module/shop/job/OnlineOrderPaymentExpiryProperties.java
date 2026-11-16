package com.petcare.module.shop.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.online-order-payment-expiry} — ST10 hủy đơn online quá hạn thanh toán (docs/adr/0146). Tham số vận
 * hành, không phải [CFG] (hạn thanh toán là [CFG] {@code shop.payment_window_hours}, đã chụp vào {@code payment_due_at}
 * lúc đặt). Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số đơn đọc một lần (mỗi đơn vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.online-order-payment-expiry")
public record OnlineOrderPaymentExpiryProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
