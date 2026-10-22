package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/me/addresses} (customer-v1 {@code AddressRequest}, UC06). Độ dài theo V1 bảng
 * {@code addresses}. {@code isDefault} null = không; sổ đang rỗng thì địa chỉ đầu luôn là mặc định (customer-v1 A6).
 * Giới hạn số địa chỉ là [CFG], kiểm ở service (BR-TK-18).
 */
public record AddressRequest(
        @Schema(description = "Tên người nhận", example = "Nguyễn Thị Lan")
        @NotBlank(message = "Vui lòng nhập tên người nhận")
        @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
        String receiverName,

        @Schema(description = "SĐT người nhận, 10 chữ số", example = "0901234567")
        @NotNull(message = "Vui lòng nhập số điện thoại người nhận")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại người nhận phải có dạng 0xxxxxxxxx")
        String receiverPhone,

        @Schema(description = "Số nhà, đường", example = "12 Trần Thái Tông")
        @NotBlank(message = "Vui lòng nhập địa chỉ")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        String addressLine,

        @Schema(description = "Phường / xã", example = "Dịch Vọng Hậu")
        @Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
        String ward,

        @Schema(description = "Tỉnh / thành phố", example = "Hà Nội")
        @NotBlank(message = "Vui lòng nhập tỉnh/thành phố")
        @Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
        String province,

        @Schema(description = "Đặt làm địa chỉ mặc định; địa chỉ đầu tiên luôn mặc định (BR-TK-18)", example = "false")
        Boolean isDefault) {
}
