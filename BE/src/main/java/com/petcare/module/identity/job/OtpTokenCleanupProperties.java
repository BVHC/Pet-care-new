package com.petcare.module.identity.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.otp-token-cleanup} (docs/adr/0111, trả D012). Tham số vận hành, không phải [CFG]: không có trong 02 và
 * ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * <p>Bất biến: {@code retentionDays × 1440 ≥ max_value} của [CFG] {@code otp.send_window_minutes} (hiện 1440, V2), để mã
 * bị xóa luôn nằm ngoài cửa sổ đếm quota BR-TK-07. {@code @Positive} đảm bảo tối thiểu 1 ngày.
 *
 * @param cron          biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param retentionDays số ngày giữ mã sau {@code expires_at}
 * @param batchSize     số dòng xóa trong một transaction
 * @param timeBudget    thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.otp-token-cleanup")
public record OtpTokenCleanupProperties(
        @NotBlank String cron,
        @Positive int retentionDays,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
