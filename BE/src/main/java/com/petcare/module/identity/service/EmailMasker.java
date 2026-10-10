package com.petcare.module.identity.service;

/**
 * Che email nhận mã liên kết hồ sơ ({@code OtpSentResponse.maskedEmail}, docs/adr/0027 — giả định, đặc tả không quy định
 * dạng che): giữ 2 ký tự đầu phần tên (1 ký tự nếu phần tên ≤ 2 ký tự), thay phần còn lại bằng {@code ***}, giữ nguyên
 * domain. Ví dụ {@code nguyen.van.a@gmail.com → ng***@gmail.com}, {@code ab@x.vn → a***@x.vn}. Chuỗi không có
 * {@code @} hợp lệ → {@code ***}, không lộ gì.
 */
final class EmailMasker {

    private static final String MASK = "***";

    private EmailMasker() {
    }

    static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return MASK;
        }
        String local = email.substring(0, at);
        int keep = local.codePointCount(0, local.length()) > 2 ? 2 : 1;
        return local.substring(0, local.offsetByCodePoints(0, keep)) + MASK + email.substring(at);
    }
}
