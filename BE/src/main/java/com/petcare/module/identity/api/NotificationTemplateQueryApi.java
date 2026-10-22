package com.petcare.module.identity.api;

import java.util.List;
import java.util.Optional;

/**
 * Owner: identity (QT, NotificationTemplate — 06 §7 G1) · BE-1. Caller: care (worker ST20 dựng nội dung thông báo).
 * Chỉ đọc, gọi được trong hoặc ngoài transaction. ADMIN sửa nội dung / khôi phục mặc định qua UC10 (docs/adr/0037): mẫu
 * đã lưu chỉ chứa dấu ngoặc nhọn ở dạng {@code {ten}} với tên thuộc {@code allowedVars} và có đủ {@code requiredVars};
 * bản sửa có hiệu lực từ lần đọc sau khi transaction sửa đã commit (kể cả dòng outbox đã xếp hàng trước đó).
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
