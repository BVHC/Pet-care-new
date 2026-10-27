package com.petcare.module.visit.service;

/**
 * Kết quả xử lý một bản ghi của ST04 (docs/adr/0082) và ST18 quá hạn tái chủng (docs/adr/0083, không bao giờ trả
 * {@link #REMINDED}); dùng chung cho nhắc tái chủng, nhắc tái khám và sinh task quá hạn.
 */
public enum ReminderResult {
    /** Đã đặt cờ "đã nhắc" và ghi outbox (email và / hoặc trong app). */
    REMINDED,
    /** Đã đặt cờ "đã nhắc" và sinh Care Task gọi điện: hồ sơ không có kênh thông báo nào (BR-TB-02). */
    TASK_CREATED,
    /** Đã đặt cờ, không gửi và không sinh task: thú đã mất (G3) hoặc hồ sơ không có kênh lẫn SĐT. */
    NOT_SENT,
    /** Không làm gì: đã nhắc, đã bị thay thế / hủy, ngoài cửa sổ, hoặc đang bị luồng khác khóa. */
    SKIPPED
}
