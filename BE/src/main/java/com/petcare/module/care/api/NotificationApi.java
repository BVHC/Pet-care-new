package com.petcare.module.care.api;

import java.util.Map;

/**
 * Owner: care (TB) · BE-1. Mọi module gọi để gửi email hoặc thông báo trong app.
 * Chỉ ghi {@code notification_outbox} trong transaction của caller; worker ST20 gửi email (docs/adr/0012) và giao
 * thông báo trong app (docs/adr/0014) sau, nên rollback nghiệp vụ thì không gửi nhầm.
 */
public interface NotificationApi {

    enum Channel { EMAIL, IN_APP }

    /**
     * Người nhận theo kênh (docs/adr/0012): {@code EMAIL} bắt buộc {@code recipientEmail} — địa chỉ chốt tại thời
     * điểm sự kiện (ví dụ email cũ khi đổi email, BR-TK-16), worker không tra lại theo tài khoản; {@code IN_APP} bắt
     * buộc {@code recipientAccountId}. Tài khoản {@code PENDING} không truyền {@code recipientAccountId} (FK chặn ST02
     * xóa tài khoản). {@code IN_APP} (docs/adr/0014): worker tạo một dòng {@code notifications} với {@code type} = mã
     * mẫu bỏ hậu tố {@code _APP}, {@code title} = {@code subject} của mẫu (mẫu IN_APP bắt buộc có subject; dài quá 200
     * ký tự thì bị cắt), {@code link_url} = {@code linkUrl} (tối đa 500 ký tự, dài hơn thì dòng bị {@code FAILED}).
     * {@code payload} phải đủ biến bắt buộc của mẫu (BR-QT-14). Khóa chứa {@code otp}, {@code mat_khau},
     * {@code password}, {@code token}, {@code secret} bị xóa khỏi outbox sau khi gửi xong.
     * Ghi trong transaction của caller: chạy lại use case thì ghi lại — chống trùng là việc của use case (cờ "đã
     * nhắc", rule, FSM).
     */
    record NotificationRequest(String templateCode, Channel channel, Long recipientAccountId,
                               String recipientEmail, Map<String, Object> payload, String linkUrl) {}

    void enqueue(NotificationRequest request);
}
