package com.petcare.module.ai.chat;

import java.util.regex.Pattern;

/**
 * Che email, số điện thoại (`0…`, `+84…`) và dãy 9 hoặc 12 chữ số (giấy tờ) trước khi nhúng, trước khi gọi LLM và trước
 * khi ghi nhật ký (BR-AI-12a). Tên thú (BR-AI-12b) do bước riêng của {@code /me} xử lý.
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(?:\\+84|0)(?:[ .-]?\\d){9}(?!\\d)");
    private static final Pattern ID_NUMBER = Pattern.compile("(?<!\\d)(?:\\d{12}|\\d{9})(?!\\d)");

    private PiiMasker() {
    }

    public static String mask(String text) {
        String masked = EMAIL.matcher(text).replaceAll("[email]");
        masked = PHONE.matcher(masked).replaceAll("[sđt]");
        return ID_NUMBER.matcher(masked).replaceAll("[số]");
    }
}
