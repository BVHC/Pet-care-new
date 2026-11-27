package com.petcare.module.care.service;

/**
 * Một dòng email luồng HIGH (OTP, mật khẩu tạm — {@link DeliveryLane#HIGH_TEMPLATES}) vừa được ghi vào outbox
 * (docs/adr/0154). Phát trong transaction của bên gọi {@code NotificationApi.enqueue}; listener chỉ nhận sau commit để
 * gửi ngay, nên rollback thì không có gì được gửi. Sự kiện nội bộ module {@code care}, không thuộc {@code api}.
 */
public record PriorityEmailQueued(Long outboxId) {
}
