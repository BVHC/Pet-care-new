package com.petcare.module.care.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.jobs.notification-outbox} (docs/adr/0012, docs/adr/0014, docs/adr/0154). Tham số vận hành của ST20, không
 * phải [CFG]: 02 không có và ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động.
 *
 * @param cron               lịch luồng NORMAL (cron 6 trường, giờ Việt Nam; {@code "-"} tắt)
 * @param priorityCron       lịch luồng HIGH (OTP, mật khẩu tạm); là đường dự phòng của lần kích gửi ngay sau commit
 * @param timeBudget         thời gian tối đa một lượt NORMAL, kiểm giữa các lô
 * @param priorityTimeBudget thời gian tối đa một lượt HIGH
 * @param maxAttempts        số lần gửi tối đa của một dòng; lần cuối lỗi → {@code FAILED}
 * @param initialBackoff     giãn cách thử lại đầu tiên, nhân đôi mỗi lần
 * @param mailFrom           địa chỉ người gửi
 * @param lagWarn            dòng NORMAL quá hạn lâu hơn mức này → log WARN {@code NOTIFICATION_OUTBOX_LAGGING}
 * @param lagWarnPriority    như trên cho luồng HIGH
 * @param inAppCron          lịch luồng IN_APP — giao thông báo trong app (docs/adr/0014)
 * @param inAppTimeBudget    thời gian tối đa một lượt IN_APP
 * @param lagWarnInApp       dòng IN_APP quá hạn lâu hơn mức này → log WARN {@code NOTIFICATION_OUTBOX_LAGGING}
 * @param normalBatchSize    số dòng NORMAL nhận và gửi trên một kết nối SMTP (docs/adr/0017)
 * @param priorityClaimLease hạn giữ một dòng HIGH đã nhận (docs/adr/0154); tối thiểu 60 s, lớn hơn một lần gửi tệ nhất
 *                           (kết nối 5 + ghi 10 + đọc 10 + QUIT 10 s)
 * @param normalClaimLease   hạn giữ một lô NORMAL đã nhận; phải ≥ {@code normalBatchSize × 30 s} vì
 *                           {@code JavaMailSender} gửi tiếp sau timeout của từng thư
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
        @NotNull @DurationMin(seconds = 60) Duration priorityClaimLease,
        @NotNull Duration normalClaimLease) {

    /** Thời gian tệ nhất của một thư trong lô (timeout SMTP 5 / 10 / 10 s của {@code application.yml}, làm tròn lên). */
    static final Duration WORST_SEND_PER_MESSAGE = Duration.ofSeconds(30);

    @AssertTrue(message = "normalClaimLease phải ≥ normalBatchSize × 30 s")
    public boolean isNormalClaimLeaseCoveringBatch() {
        return normalClaimLease == null
                || normalClaimLease.compareTo(WORST_SEND_PER_MESSAGE.multipliedBy(normalBatchSize)) >= 0;
    }
}
