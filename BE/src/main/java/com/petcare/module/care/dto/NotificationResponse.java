package com.petcare.module.care.dto;

import java.time.Instant;

/**
 * Một thông báo trong ứng dụng (care-v1 schema {@code Notification}, UC88). Chỉ dựng bởi {@code NotificationMapper}.
 * {@code readAt} null là chưa đọc; {@code linkUrl} null khi thông báo không kèm liên kết.
 */
public record NotificationResponse(
        Long notificationId,
        String type,
        String title,
        String body,
        String linkUrl,
        Instant readAt,
        Instant createdAt) {
}
