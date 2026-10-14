package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Tiêu đề mới, chỉ dùng biến có sẵn (BR-QT-14)", example = "Mã xác thực đăng ký Pet Care")
        @NotBlank(message = "Vui lòng nhập tiêu đề")
        @Size(max = 255, message = "Tiêu đề tối đa 255 ký tự")
        String subject,

        @Schema(description = "Nội dung mới, phải chứa đủ biến bắt buộc (BR-QT-14)",
                example = "Xin chào,\nMã xác thực của bạn là {ma_otp}, hiệu lực 5 phút.")
        @NotBlank(message = "Vui lòng nhập nội dung")
        String body) {
}
