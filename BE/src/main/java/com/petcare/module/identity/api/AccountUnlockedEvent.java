package com.petcare.module.identity.api;

/**
 * Publisher: identity (Tài khoản#8, BR-QT-11, BR-QT-12) · BE-1. Phát đồng bộ trong transaction mở khóa, sau khi cờ
 * {@code is_locked} đã được flush (listener đọc lại tài khoản qua {@link StaffDirectoryApi} phải thấy đã mở khóa).
 * Listener: visit (gỡ cờ "cần gán lại" của lượt {@code IN_PROGRESS} mà người vừa được mở khóa lại đủ điều kiện phụ
 * trách; docs/adr/0088, trả D042).
 */
public record AccountUnlockedEvent(Long accountId, Long actorId) {}
