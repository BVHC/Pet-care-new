package com.petcare.module.care.job;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.jobs.notification-cleanup} (docs/adr/0112, trả D023). Tham số vận hành, không phải [CFG]: không có trong 02
 * và ADMIN không sửa qua UC10. Sai giá trị thì app dừng lúc khởi động. Hai thời hạn là giả định của nhóm (đặc tả không
 * quy định thời hạn giữ thông báo), tính từ {@code created_at}.
 *
 * @param cron                biểu thức cron 6 trường theo giờ Việt Nam; {@code "-"} tắt job
 * @param readRetentionDays   số ngày giữ thông báo đã đọc
 * @param unreadRetentionDays số ngày giữ thông báo chưa đọc; phải lớn hơn {@code readRetentionDays}, nếu không dòng
 *                            chưa đọc bị xóa sớm hơn dòng đã đọc
 * @param batchSize           số dòng xóa trong một transaction
 * @param timeBudget          thời gian tối đa một lượt (cả hai pha); hết thì dừng, lượt sau làm tiếp
 */
@Validated
@ConfigurationProperties("app.jobs.notification-cleanup")
public record NotificationCleanupProperties(
        @NotBlank String cron,
        @Positive int readRetentionDays,
        @Positive int unreadRetentionDays,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration timeBudget) {

    @AssertTrue(message = "unreadRetentionDays phải lớn hơn readRetentionDays")
    public boolean isUnreadRetentionLongerThanRead() {
        return unreadRetentionDays > readRetentionDays;
    }
}
