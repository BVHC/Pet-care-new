package com.petcare.module.visit.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.vaccine-reminder} — ST04 nhắc tái chủng (docs/adr/0082). Tham số vận hành, không phải [CFG]: "nhắc
 * trước bao nhiêu ngày" là [CFG] {@code reminder.vaccine_days_before}. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job. Mặc định mỗi giờ từ 07:00 đến 20:00
 *                   (không gửi email ban đêm; lượt sau tự thử lại phần lỗi), giây 30 để lệch các job 5 phút
 * @param batchSize  số id đọc một lần (mỗi mũi vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.vaccine-reminder")
public record VaccineReminderProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
