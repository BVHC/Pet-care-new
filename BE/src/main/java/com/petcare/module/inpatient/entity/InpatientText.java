package com.petcare.module.inpatient.entity;

import static java.util.Objects.requireNonNull;

/**
 * Kiểm chuỗi tự thân cho entity của nội trú: không rỗng sau {@code strip()} và không quá độ dài cột. Vi phạm là lỗi lập
 * trình ({@link IllegalArgumentException}): service đã kiểm trước để trả 400 {@code BR-LT-13…16}; đây là lớp chặn cuối
 * nếu service sót (khuôn {@code workforce.entity.EntityText}, docs/adr/0138; không import vì khác module).
 */
final class InpatientText {

    /** {@code admission_orders.content} {@code VARCHAR(500)} (BR-LT-15); {@code admission_observations.note} cũng 500. */
    static final int CONTENT_MAX_LENGTH = 500;
    /** {@code done_note}, {@code cancel_reason} đều {@code VARCHAR(300)} (BR-LT-15). */
    static final int NOTE_MAX_LENGTH = 300;
    /** {@code admissions.code} {@code VARCHAR(20)}. */
    static final int CODE_MAX_LENGTH = 20;

    private InpatientText() {
    }

    /** Chuỗi bắt buộc: strip, không rỗng, không quá {@code maxLength}. */
    static String require(String value, int maxLength, String field) {
        String text = requireNonNull(value, field).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return limit(text, maxLength, field);
    }

    /** Chuỗi tùy chọn: strip, rỗng hoặc null → null. */
    static String optional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        return text.isEmpty() ? null : limit(text, maxLength, field);
    }

    private static String limit(String text, int maxLength, String field) {
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " dài quá " + maxLength + " ký tự");
        }
        return text;
    }
}
