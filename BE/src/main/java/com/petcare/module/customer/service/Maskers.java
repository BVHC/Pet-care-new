package com.petcare.module.customer.service;

/**
 * Che thông tin liên lạc trước khi trả ra ngoài (BR-KH-10, BR-TK-19). Đặc tả chỉ cho ví dụ "Ng*** V** A"; các quy tắc
 * dưới đây là giả định ghi ở docs/adr/0041 và cùng dạng với {@code identity/service/EmailMasker} (module identity không
 * export lớp này).
 */
final class Maskers {

    private static final String MASK = "***";

    private Maskers() {
    }

    /**
     * "Nguyễn Văn A" → "Ng*** V** A": từ đầu giữ 2 ký tự + {@code ***}, từ giữa giữ 1 ký tự + {@code **}, từ cuối chỉ giữ
     * chữ cái đầu. Một từ → "Ng***". Họ tên trắng → {@code ***}.
     */
    static String fullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return MASK;
        }
        String[] words = fullName.strip().split("\\s+");
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                masked.append(' ');
            }
            String word = words[i];
            if (i == 0) {
                masked.append(prefix(word, 2)).append(MASK);
            } else if (i == words.length - 1) {
                masked.append(prefix(word, 1));
            } else {
                masked.append(prefix(word, 1)).append("**");
            }
        }
        return masked.toString();
    }

    /** "nguyen.van.a@gmail.com" → "ng***@gmail.com"; phần tên ≤ 2 ký tự giữ 1; không hợp lệ → {@code ***}. */
    static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return MASK;
        }
        String local = email.substring(0, at);
        int keep = local.codePointCount(0, local.length()) > 2 ? 2 : 1;
        return prefix(local, keep) + MASK + email.substring(at);
    }

    private static String prefix(String text, int codePoints) {
        int count = Math.min(codePoints, text.codePointCount(0, text.length()));
        return text.substring(0, text.offsetByCodePoints(0, count));
    }
}
