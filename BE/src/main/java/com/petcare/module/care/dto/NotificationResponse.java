package com.petcare.module.care.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một thông báo trong ứng dụng (care-v1 schema {@code Notification}, UC88). Chỉ dựng bởi {@code NotificationMapper}.
 * {@code readAt} null là chưa đọc; {@code linkUrl} null khi thông báo không kèm liên kết.
 */
public record NotificationResponse(
        @Schema(description = "Mã thông báo", example = "128")
        Long notificationId,

        @Schema(description = "Loại thông báo (mã mẫu bỏ hậu tố _APP)", example = "ACCOUNT_LOCKED_FOLLOW_UP")
        String type,

        @Schema(description = "Tiêu đề", example = "Nhân viên bị khóa còn việc dở")
        String title,

        @Schema(description = "Nội dung", example = "Tài khoản của Lê Minh Châu đã bị khóa; lượt #901 cần gán lại.")
        String body,

        @Schema(description = "Liên kết mở khi bấm; null nếu không có", example = "/staff/queue?visitId=901")
        String linkUrl,

        @Schema(description = "Thời điểm đọc lần đầu; null = chưa đọc", example = "2026-10-14T03:05:00Z")
        Instant readAt,

        @Schema(description = "Thời điểm tạo", example = "2026-10-14T02:30:00Z")
        Instant createdAt) {
}
