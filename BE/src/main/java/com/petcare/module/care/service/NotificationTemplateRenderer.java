package com.petcare.module.care.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/**
 * Thay biến {@code {ten_bien}} của mẫu bằng giá trị trong payload outbox (BR-QT-14, docs/adr/0012). Chỉ thay biến có
 * trong {@code allowedVars}; biến tùy chọn không có giá trị → chuỗi rỗng; placeholder ngoài {@code allowedVars} giữ
 * nguyên (UC10 chặn lúc lưu mẫu).
 *
 * <p>Quét <b>một lượt</b> từ trái sang phải theo đúng ngữ pháp của {@code identity.service.TemplateVariables} (docs/adr/0037
 * mục 3): giá trị được chép thẳng vào kết quả và không bao giờ bị quét lại, nên giá trị chứa {@code {bien_khac}}, hoặc
 * ghép với chữ của mẫu thành một token, ra nguyên văn (D019). Không dùng regex: giá trị có thể chứa {@code $}, {@code \}.
 * Hai lớp ở hai module nên giữ khớp bằng {@code NotificationTemplateSyntaxParityTest}, không dùng chung code.
 */
@Component
public class NotificationTemplateRenderer {

    public record Rendered(String subject, String body) {}

    /** Biến bắt buộc của mẫu mà payload không có (hoặc là {@code null}); rỗng = render được. */
    public List<String> missingRequired(TemplateView template, Map<String, Object> payload) {
        return template.requiredVars().stream().filter(name -> payload.get(name) == null).toList();
    }

    public Rendered render(TemplateView template, Map<String, Object> payload) {
        Set<String> allowed = Set.copyOf(template.allowedVars());
        return new Rendered(fill(template.subject(), allowed, payload), fill(template.body(), allowed, payload));
    }

    private static String fill(String text, Set<String> allowed, Map<String, Object> payload) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int close = c == '{' ? closingBrace(text, i + 1) : -1;
            if (close < 0) {
                result.append(c);
                i++;
                continue;
            }
            String name = text.substring(i + 1, close);
            if (allowed.contains(name)) {
                Object value = payload.get(name);
                if (value != null) {
                    result.append(value);
                }
            } else {
                result.append(text, i, close + 1);
            }
            i = close + 1;
        }
        return result.toString();
    }

    /** Vị trí {@code }} đầu tiên từ {@code from} trước khi gặp {@code {} khác; -1 nếu không có. */
    private static int closingBrace(String text, int from) {
        for (int j = from; j < text.length(); j++) {
            char c = text.charAt(j);
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
