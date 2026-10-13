package com.petcare.module.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PUT /api/notification-templates/{code}} (identity-v1 {@code UpdateTemplateRequest}, UC10 —
 * docs/adr/0037). Chỉ kiểm hình thức: tiêu đề bắt buộc với mọi kênh, tối đa 255 = {@code notification_templates.subject}
 * (erd §1; {@code @Size} đếm theo UTF-16 nên không bao giờ vượt cột); nội dung bắt buộc, không giới hạn độ dài (cột
 * {@code TEXT}, kích thước body thuộc nợ D018). Biến (BR-QT-14) phụ thuộc mẫu nên kiểm ở service. Lưu nguyên văn, không
 * chuẩn hóa; số / boolean JSON được Jackson ép thành chuỗi; trường lạ bị bỏ qua.
 */
public record UpdateTemplateRequest(
        @NotBlank(message = "Vui lòng nhập tiêu đề")
        @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
        String subject,

        @NotBlank(message = "Vui lòng nhập nội dung")
        String body) {
}
