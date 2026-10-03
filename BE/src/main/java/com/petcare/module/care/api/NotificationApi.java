package com.petcare.module.care.api;

import java.util.Map;

/**
 * Owner: care (TB) · BE-1. Mọi module gọi để gửi email hoặc thông báo trong app.
 * Chỉ ghi {@code notification_outbox} trong transaction của caller; worker ST20 gửi và thử lại sau,
 * nên rollback nghiệp vụ thì không gửi nhầm.
 */
public interface NotificationApi {

    enum Channel { EMAIL, IN_APP }

    /**
     * Cần {@code recipientAccountId} hoặc {@code recipientEmail} (hồ sơ tại quầy chỉ có email).
     * {@code payload} phải đủ biến bắt buộc của mẫu (BR-QT-14).
     */
    record NotificationRequest(String templateCode, Channel channel, Long recipientAccountId,
                               String recipientEmail, Map<String, Object> payload, String linkUrl) {}

    void enqueue(NotificationRequest request);
}
