package com.petcare.module.workforce.entity;

import static java.util.Objects.requireNonNull;

/**
 * Kiểm chuỗi tự thân cho entity của workforce: không rỗng sau {@code strip()} và không quá độ dài cột. Vi phạm là lỗi
 * lập trình ({@link IllegalArgumentException}): service đã kiểm trước để trả 400 {@code VALIDATION_FAILED} /
 * {@code BR-NS-01}; đây là lớp chặn cuối nếu service sót (docs/adr/0149).
 */
final class EntityText {

    /** {@code reason}, {@code decision_note} đều {@code VARCHAR(300)} (BR-NS-02, 04, 06, 07). */
    static final int NOTE_MAX_LENGTH = 300;

    private EntityText() {
    }

    static String require(String value, int maxLength, String field) {
        String text = requireNonNull(value, field).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " dài quá " + maxLength + " ký tự");
        }
        return text;
    }
}
