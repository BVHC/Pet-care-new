package com.petcare.module.care.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/**
 * Thay biến {@code {ten_bien}} của mẫu bằng giá trị trong payload outbox (BR-QT-14, docs/adr/0012). Chỉ thay biến có
 * trong {@code allowedVars}; biến tùy chọn không có giá trị → chuỗi rỗng; placeholder ngoài {@code allowedVars} giữ
 * nguyên (UC10 chặn lúc lưu mẫu). Thay bằng {@link String#replace}, không regex: giá trị có thể chứa {@code $}, {@code \}.
 */
@Component
public class NotificationTemplateRenderer {

    public record Rendered(String subject, String body) {}

    /** Biến bắt buộc của mẫu mà payload không có (hoặc là {@code null}); rỗng = render được. */
    public List<String> missingRequired(TemplateView template, Map<String, Object> payload) {
        return template.requiredVars().stream().filter(name -> payload.get(name) == null).toList();
    }

    public Rendered render(TemplateView template, Map<String, Object> payload) {
        return new Rendered(fill(template.subject(), template, payload), fill(template.body(), template, payload));
    }

    private static String fill(String text, TemplateView template, Map<String, Object> payload) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (String name : template.allowedVars()) {
            Object value = payload.get(name);
            result = result.replace("{" + name + "}", value == null ? "" : String.valueOf(value));
        }
        return result;
    }
}
