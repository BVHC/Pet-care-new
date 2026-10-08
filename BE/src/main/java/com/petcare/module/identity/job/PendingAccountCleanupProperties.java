package com.petcare.module.identity.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.pending-account-cleanup} — ST02 (docs/adr/0013). Tham số vận hành, không phải [CFG]: thời hạn
 * {@code PENDING} là [CFG] {@code account.pending_ttl_hours}, chốt vào {@code accounts.pending_expires_at} lúc đăng ký.
 * Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param batchSize  số id đọc một lần (mỗi tài khoản vẫn xóa trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 * @param slowWarn   xóa một tài khoản lâu hơn mức này → log WARN {@code PENDING_ACCOUNT_CLEANUP_SLOW} (nợ D009)
 */
@Validated
@ConfigurationProperties("app.jobs.pending-account-cleanup")
public record PendingAccountCleanupProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget,
        @NotNull @DurationMin(millis = 1) Duration slowWarn) {
}
