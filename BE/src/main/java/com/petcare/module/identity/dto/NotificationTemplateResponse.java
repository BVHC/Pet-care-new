package com.petcare.module.identity.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.care.api.NotificationApi.Channel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Schema {@code NotificationTemplate} của identity-v1 (#33, #34, #35; UC10 — docs/adr/0037). Tên class khác tên schema
 * chỉ để không trùng entity {@code NotificationTemplate}; JSON giống hệt. {@code subject}, {@code body} là bản
 * <b>đang dùng</b>, không phải bản mặc định; {@code isDefault} = cả hai trùng từng ký tự với bản mặc định.
 * {@code isDefault} cần {@code @JsonProperty} như {@code AddressResponse.isDefault} để JSON giữ đúng tên của hợp đồng.
 * Không có {@code default_*}, {@code updated_by}, {@code updated_at}: hợp đồng không có, lịch sử sửa xem ở audit (UC11).
 */
public record NotificationTemplateResponse(
        @Schema(description = "Mã mẫu", example = "OTP_REGISTER")
        String code,

        @Schema(description = "Kênh gửi", example = "EMAIL")
        Channel channel,

        @Schema(description = "Tiêu đề hiện tại", example = "Mã xác thực đăng ký Pet Care")
        String subject,

        @Schema(description = "Nội dung hiện tại, biến viết dạng {ten_bien}",
                example = "Xin chào,\nMã xác thực của bạn là {ma_otp}.")
        String body,

        @Schema(description = "Biến được dùng")
        List<String> allowedVars,

        @Schema(description = "Biến bắt buộc phải có (BR-QT-14)")
        List<String> requiredVars,

        @Schema(description = "Tiêu đề và nội dung đang trùng bản mặc định", example = "true")
        @JsonProperty("isDefault") boolean isDefault) {
}
