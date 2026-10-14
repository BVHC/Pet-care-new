package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/me/link/otp} (identity-v1 {@code LinkOtpRequest}, UC07). Chỉ kiểm hình thức; BR-TK-19,
 * BR-TK-07 kiểm ở service (convention 06). {@code phone}: SĐT dùng để tìm hồ sơ ở {@code GET /api/me/link-candidates};
 * bỏ trống = SĐT đã khai trên hồ sơ online. Server chỉ gửi mã khi {@code customerId} nằm trong danh sách ứng viên của
 * SĐT đó (docs/adr/0027). Định dạng theo kiểu {@code phone} của contract ({@code ^0[0-9]{9,10}$}).
 */
public record LinkOtpRequest(
        @Schema(description = "Mã hồ sơ tại quầy muốn liên kết", example = "315")
        @NotNull(message = "Vui lòng chọn hồ sơ cần liên kết")
        @Positive(message = "Mã hồ sơ không hợp lệ")
        Long customerId,

        @Schema(description = "SĐT của hồ sơ tại quầy; bỏ trống = SĐT của hồ sơ online", example = "0901234567")
        @Pattern(regexp = "^0[0-9]{9,10}$", message = "Số điện thoại phải gồm 10–11 chữ số, bắt đầu bằng 0")
        String phone) {
}
