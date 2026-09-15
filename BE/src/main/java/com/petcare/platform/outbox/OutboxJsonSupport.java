package com.petcare.platform.outbox;

/**
 * Escape tối thiểu cho JSON string tự build bằng tay (không dùng Jackson vì
 * payload {@link OutboxEvent} chỉ cần vài field đơn giản). Dùng chung cho mọi
 * module ghi Outbox event bằng chuỗi thủ công (tránh copy lại logic escape ở
 * từng nơi — trích từ {@code AccountEventRecorder}, chỗ đầu tiên cần escape
 * vì {@code reason} ở DeactivateRequest/ReactivateRequest là free-text do
 * người dùng nhập).
 */
public final class OutboxJsonSupport {

    private OutboxJsonSupport() {
    }

    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
