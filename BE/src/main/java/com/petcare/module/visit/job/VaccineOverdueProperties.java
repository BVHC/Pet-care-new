package com.petcare.module.visit.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.vaccine-overdue} — ST18 quá hạn tái chủng (docs/adr/0083). Tham số vận hành, không phải [CFG]: "quá hạn
 * bao nhiêu ngày" là [CFG] {@code reminder.vaccine_overdue_days}. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job. Mặc định mỗi giờ từ 07:00 đến 20:00
 *                   (lượt sau tự thử lại phần lỗi), phút 25 giây 30: lệch các job 5 phút và lệch ST04 (phút 10 / 40)
 * @param batchSize  số id đọc một lần (mỗi mũi vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.vaccine-overdue")
public record VaccineOverdueProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
