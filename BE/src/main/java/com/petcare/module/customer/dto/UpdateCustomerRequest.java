package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/customers/{customerId}} (customer-v1 {@code UpdateCustomerRequest}, UC22). Quy ước PATCH của
 * module (docs/adr/0028): {@code null} = giữ, rỗng sau {@code strip} = xóa. {@code fullName} không xóa được; hồ sơ tại
 * quầy không xóa được SĐT (BR-KH-01); {@code email} chỉ sửa được khi hồ sơ chưa gắn tài khoản (BR-TK-16, A5).
 * {@code fullName} rỗng → 400 {@code VALIDATION_FAILED}; {@code phone} / {@code email} rỗng ("") nghĩa là xóa nên bỏ qua
 * kiểm định dạng.
 */
public record UpdateCustomerRequest(
        @Schema(description = "Họ tên mới; null = giữ nguyên", example = "Nguyễn Thị Lan Anh")
        @Pattern(regexp = "(?s).*\\S.*", message = "Họ tên không được để trống")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        String fullName,

        @Schema(description = "SĐT mới; null = giữ nguyên, rỗng = xóa (hồ sơ tại quầy không xóa được)",
                example = "0901234567")
        @Pattern(regexp = "^(\\s*|0\\d{9,10})$", message = "Số điện thoại phải có 10–11 chữ số và bắt đầu bằng 0")
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
        String phone,

        @Schema(description = "Email mới; chỉ khi hồ sơ chưa gắn tài khoản (A5); null = giữ nguyên, rỗng = xóa",
                example = "lan.nguyen@gmail.com")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email) {
}
