package com.petcare.module.ai.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Chữ thường không dấu để so khớp giống (07 §8: "viết thường không dấu") và tìm theo tên ở UC94. NFD không tách {@code đ},
 * nên đổi riêng, để khớp cách người nhập từ khóa gõ "dom" cho "Đốm".
 */
final class PlainText {

    private PlainText() {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replace('đ', 'd');
    }
}
