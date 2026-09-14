package com.petcare.module.notification.service;

import com.petcare.platform.enums.NotificationChannel;

import java.util.UUID;

/**
 * Module 23 (Notification) tối giản — chỉ đủ cho Command SendNotification /
 * SendRegistrationOTP (RULE-23-01, docs/06-erd.md §Ma trận Đối soát dòng 1375).
 *
 * Tách 2 bước enqueue/dispatch (không phải 1 method "sendNotification" gọi
 * cả 2 liền) vì lý do transactional: {@link #enqueue} chỉ ghi DB (nhanh,
 * tham gia transaction của caller — vd AuthServiceImpl.registerAccount()),
 * còn {@link #dispatch} gọi I/O ngoài (SMTP) — PHẢI được gọi như 1 lời gọi
 * ngoài (qua Spring proxy) SAU KHI transaction enqueue đã commit, để 1 lỗi
 * SMTP tạm thời không rollback luôn việc tạo Account/Otp. Caller điển hình
 * (AuthController): enqueue() trong lúc gọi authService.registerAccount(),
 * rồi dispatch(taskId, toAddress) ngay sau khi service đó trả về (transaction
 * đã commit).
 *
 * `notification_tasks` (docs/06-erd.md §3.7) chỉ lưu `recipient_user_id`, không
 * có cột địa chỉ đích (email/số điện thoại) — vì vậy {@code toAddress} do
 * caller (module sở hữu dữ liệu liên hệ, vd Auth với accounts.email) truyền
 * vào tại thời điểm dispatch, thay vì Notification tự tra cứu ngược sang
 * module khác (giữ đúng ranh giới module — 01-package-structure.md).
 */
public interface NotificationService {

    UUID enqueue(UUID recipientUserId, NotificationChannel channel, String eventType, String content);

    void dispatch(UUID taskId, String toAddress);
}
