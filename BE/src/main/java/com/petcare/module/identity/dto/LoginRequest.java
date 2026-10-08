package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/login} (identity-v1 {@code LoginRequest}, UC03). Chỉ kiểm hình thức; lỗi ở đây không
 * tính là một lần đăng nhập sai (không đếm, không audit). Mật khẩu không giới hạn độ dài ở đây: quá 72 byte xử lý ở
 * service như sai mật khẩu (docs/adr/0019 mục 7), vì {@code @Size} đếm ký tự chứ không đếm byte. Mật khẩu không bị cắt
 * khoảng trắng (như đăng ký).
 */
public record LoginRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
