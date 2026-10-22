package com.petcare.module.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/customers} (customer-v1 {@code CreateCounterCustomerRequest}, UC22). Hồ sơ tại quầy bắt buộc
 * họ tên và SĐT (BR-KH-01); SĐT không duy nhất nên trùng hồ sơ khác vẫn tạo được (BR-KH-10). Email không bắt buộc, được
 * lưu chữ thường.
 */
public record CreateCounterCustomerRequest(
        @Schema(description = "Họ tên", example = "Nguyễn Thị Lan")
        @NotBlank(message = "Vui lòng nhập họ tên")
        @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
        String fullName,

        @Schema(description = "SĐT, 10–11 chữ số, bắt đầu bằng 0; không cần duy nhất (BR-KH-01)", example = "0901234567")
        @NotNull(message = "Vui lòng nhập số điện thoại")
        @Pattern(regexp = "^0\\d{9,10}$", message = "Số điện thoại phải có 10–11 chữ số và bắt đầu bằng 0")
        @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
        String phone,

        @Schema(description = "Email của hồ sơ (không bắt buộc)", example = "lan.nguyen@gmail.com")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email) {
}
