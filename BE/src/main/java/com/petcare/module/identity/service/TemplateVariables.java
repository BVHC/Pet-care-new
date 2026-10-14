package com.petcare.module.identity.service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bộ quét biến của mẫu thông báo (BR-QT-14; docs/adr/0037 mục 3). Ngữ pháp chặt: dấu ngoặc nhọn chỉ dùng để viết biến
 * {@code {ten_bien}} với tên nằm trong {@code allowedVars} (khớp chính xác, phân biệt hoa thường); mọi {@code {} hay
 * {@code }} khác là lỗi.
 *
 * <p>Phải khớp với {@code care.service.NotificationTemplateRenderer}, vốn chỉ thay đúng chuỗi {@code {ten}} cho từng tên
 * trong {@code allowedVars}: mẫu qua bộ quét thì nội dung gửi đi không còn dấu ngoặc nào, và biến được quét thấy đúng là
 * biến được thay. Hai lớp ở hai module nên giữ khớp bằng {@code NotificationTemplateSyntaxParityTest}, không dùng chung
 * code (06 §1: {@code api/} chỉ chứa interface, record, enum, event).
 */
final class TemplateVariables {

    private TemplateVariables() {
    }

    /**
     * @param used    tên biến hợp lệ có mặt (không trùng, theo thứ tự xuất hiện)
     * @param unknown token dạng {@code {…}} có tên ngoài {@code allowedVars}, viết kèm ngoặc (không trùng, theo thứ
     *                tự xuất hiện)
     * @param strayBrace có {@code {} không đóng hoặc {@code }} không mở
     */
    record Scan(Set<String> used, List<String> unknown, boolean strayBrace) {

        boolean valid() {
            return unknown.isEmpty() && !strayBrace;
        }
    }

    /** Quét một lượt từ trái sang phải; {@code text} {@code null} coi như rỗng. */
    static Scan scan(String text, Collection<String> allowedVars) {
        Set<String> used = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        boolean stray = false;
        String source = text == null ? "" : text;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '}') {
                stray = true;
                i++;
            } else if (c == '{') {
                int close = closingBrace(source, i + 1);
                if (close < 0) {
                    stray = true;
                    i++;
                } else {
                    String name = source.substring(i + 1, close);
                    if (allowedVars.contains(name)) {
                        used.add(name);
                    } else {
                        unknown.add("{" + name + "}");
                    }
                    i = close + 1;
                }
            } else {
                i++;
            }
        }
        return new Scan(Collections.unmodifiableSet(used), List.copyOf(unknown), stray);
    }

    /** Vị trí {@code }} đầu tiên từ {@code from} trước khi gặp {@code {} khác; -1 nếu không có. */
    private static int closingBrace(String source, int from) {
        for (int j = from; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '}') {
                return j;
            }
            if (c == '{') {
                return -1;
            }
        }
        return -1;
    }
}
