package com.petcare.module.visit.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.follow-up-reminder} — ST04 nhắc tái khám (docs/adr/0082). Tham số vận hành, không phải [CFG]: "nhắc
 * trước bao nhiêu ngày" là [CFG] {@code reminder.follow_up_days_before}. Cùng ý nghĩa các trường với
 * {@link VaccineReminderProperties}; cron mặc định lệch 30 phút để hai job không cùng chiếm thread scheduler.
 */
@Validated
@ConfigurationProperties("app.jobs.follow-up-reminder")
public record FollowUpReminderProperties(
        @NotBlank String cron,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {
}
