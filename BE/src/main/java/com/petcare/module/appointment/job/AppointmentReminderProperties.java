package com.petcare.module.appointment.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.appointment-reminder} — ST03 (docs/adr/0056). Tham số vận hành, không phải [CFG]: "nhắc trước bao
 * nhiêu giờ" là [CFG] {@code appointment.reminder_hours_before}. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron       biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job. Mặc định lệch phút với ST05 / ST02
 *                   (Q11) để các job dài không cùng chiếm thread scheduler
 * @param batchSize  số id đọc một lần (mỗi lịch vẫn xử lý trong transaction riêng)
 * @param timeBudget thời gian tối đa một lượt; hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.appointment-reminder")
public record AppointmentReminderProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
