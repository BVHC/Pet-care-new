package com.petcare.module.care.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.jobs.notification-outbox} (docs/adr/0012, docs/adr/0014). Tham số vận hành của ST20, không phải [CFG]:
 * 02 không có và ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron               lịch luồng NORMAL (cron 6 trường, giờ Việt Nam; {@code "-"} tắt)
 * @param priorityCron       lịch luồng HIGH (OTP, mật khẩu tạm)
 * @param timeBudget         thời gian tối đa một lượt NORMAL
 * @param priorityTimeBudget thời gian tối đa một lượt HIGH
 * @param maxAttempts        số lần gửi tối đa của một dòng; lần cuối lỗi → {@code FAILED}
 * @param initialBackoff     giãn cách thử lại đầu tiên, nhân đôi mỗi lần
 * @param mailFrom           địa chỉ người gửi
 * @param lagWarn            dòng NORMAL quá hạn lâu hơn mức này → log WARN {@code NOTIFICATION_OUTBOX_LAGGING}
 * @param lagWarnPriority    như trên cho luồng HIGH
 * @param inAppCron          lịch luồng IN_APP — giao thông báo trong app (docs/adr/0014)
 * @param inAppTimeBudget    thời gian tối đa một lượt IN_APP
 * @param lagWarnInApp       dòng IN_APP quá hạn lâu hơn mức này → log WARN {@code NOTIFICATION_OUTBOX_LAGGING}
 * @param normalBatchSize    số dòng NORMAL khóa và gửi trên một kết nối SMTP trong một transaction (docs/adr/0017)
 * @param normalBatchSendBudget thời gian gửi tối đa của một lô, tính cả lúc kết nối; tối đa 15 s để transaction giữ
 *                           khóa không vượt {@code idle_in_transaction_session_timeout} 60 s (docs/adr/0017)
 */
@Validated
@ConfigurationProperties("app.jobs.notification-outbox")
public record NotificationOutboxProperties(
        @NotBlank String cron,
        @NotBlank String priorityCron,
        @NotNull @DurationMin(millis = 1) Duration timeBudget,
        @NotNull @DurationMin(millis = 1) Duration priorityTimeBudget,
        @Min(1) int maxAttempts,
        @NotNull @DurationMin(seconds = 1) Duration initialBackoff,
        @NotBlank @Email String mailFrom,
        @NotNull @DurationMin(seconds = 1) Duration lagWarn,
        @NotNull @DurationMin(seconds = 1) Duration lagWarnPriority,
        @NotBlank String inAppCron,
        @NotNull @DurationMin(millis = 1) Duration inAppTimeBudget,
        @NotNull @DurationMin(seconds = 1) Duration lagWarnInApp,
        @Min(1) @Max(100) int normalBatchSize,
        @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 15) Duration normalBatchSendBudget) {
}
