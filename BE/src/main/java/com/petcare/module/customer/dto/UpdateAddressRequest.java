package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/me/addresses/{addressId}} (customer-v1 {@code UpdateAddressRequest}, UC06). Mọi trường
 * {@code null} = giữ nguyên; trường bắt buộc gửi rỗng → 400; {@code ward} rỗng = xóa. Cờ mặc định không đổi ở đây
 * (dùng {@code set-default}); {@code isDefault} gửi kèm bị bỏ qua như mọi trường lạ.
 */
public record UpdateAddressRequest(
        @Schema(description = "Tên người nhận; null = giữ nguyên", example = "Nguyễn Thị Lan")
        @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên người nhận không được để trống")
        String receiverName,

        @Schema(description = "SĐT người nhận; null = giữ nguyên", example = "0901234567")
        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại người nhận phải có dạng 0xxxxxxxxx")
        String receiverPhone,

        @Schema(description = "Số nhà, đường; null = giữ nguyên", example = "15 Trần Thái Tông")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Địa chỉ không được để trống")
        String addressLine,

        @Schema(description = "Phường / xã; null = giữ nguyên", example = "Dịch Vọng Hậu")
        @Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
        String ward,

        @Schema(description = "Tỉnh / thành phố; null = giữ nguyên", example = "Hà Nội")
        @Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tỉnh/thành phố không được để trống")
        String province) {
}
