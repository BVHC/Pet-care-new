package com.petcare.module.identity.api;

import java.util.List;
import java.util.Optional;

/**
 * Owner: identity (QT, NotificationTemplate — 06 §7 G1) · BE-1. Caller: care (worker ST20 dựng nội dung thông báo).
 * Chỉ đọc, gọi được trong hoặc ngoài transaction. Sửa mẫu (UC10, BR-QT-14) làm ở task QT sau.
 */
public interface NotificationTemplateQueryApi {

    /**
     * Mẫu đang dùng ({@code body}, {@code subject} — không phải bản mặc định). Biến viết dạng {@code {ten_bien}};
     * {@code requiredVars} ⊆ {@code allowedVars}. {@code channel} là {@code EMAIL} hoặc {@code IN_APP}.
     */
    record TemplateView(String code, String channel, String subject, String body, List<String> allowedVars,
                        List<String> requiredVars) {}

    Optional<TemplateView> findTemplate(String code);
}
