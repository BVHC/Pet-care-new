package com.petcare.module.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/me/addresses/{addressId}} (customer-v1 {@code UpdateAddressRequest}, UC06). Mọi trường
 * {@code null} = giữ nguyên; trường bắt buộc gửi rỗng → 400; {@code ward} rỗng = xóa. Cờ mặc định không đổi ở đây
 * (dùng {@code set-default}); {@code isDefault} gửi kèm bị bỏ qua như mọi trường lạ.
 */
public record UpdateAddressRequest(
        @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tên người nhận không được để trống")
        String receiverName,

        @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại người nhận phải có dạng 0xxxxxxxxx")
        String receiverPhone,

        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Địa chỉ không được để trống")
        String addressLine,

        @Size(max = 100, message = "Phường/xã tối đa 100 ký tự")
        String ward,

        @Size(max = 100, message = "Tỉnh/thành phố tối đa 100 ký tự")
        @Pattern(regexp = "(?s).*\\S.*", message = "Tỉnh/thành phố không được để trống")
        String province) {
}
