package com.petcare.module.customer.service;

/** Quy ước PATCH của module (docs/adr/0028, như docs/adr/0026): {@code null} = giữ, rỗng sau {@code strip} = xóa. */
final class Values {

    private Values() {
    }

    /** Trường cho phép NULL: {@code null} = giữ {@code current}; rỗng sau {@code strip} = xóa; còn lại = giá trị mới. */
    static String optional(String requested, String current) {
        if (requested == null) {
            return current;
        }
        String stripped = requested.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Trường bắt buộc: {@code null} = giữ {@code current}; còn lại = giá trị đã {@code strip} (record đã chặn rỗng). */
    static String required(String requested, String current) {
        return requested == null ? current : requested.strip();
    }
}
