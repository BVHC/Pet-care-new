package com.petcare.module.content.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Hàm thuần xử lý chữ của module content. Độ dài đếm theo code point để khớp {@code char_length} của CHECK trong
 * Postgres (emoji là 1, không phải 2 như {@code String.length()}); chữ có dấu chuẩn hóa NFC trước khi đếm và lưu.
 */
final class ContentText {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private ContentText() {
    }

    /** NFC rồi {@code strip()}; {@code null} giữ nguyên. */
    static String normalize(String text) {
        return text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }

    static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Thoát {@code \}, {@code %}, {@code _} để dùng trong {@code LIKE ... ESCAPE '\'}. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Giả định A2 của T32: chữ thường, số, gạch ngang đơn giữa các nhóm. */
    static boolean isSlug(String slug) {
        return SLUG.matcher(slug).matches();
    }
}
