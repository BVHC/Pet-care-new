package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/register/verify} (identity-v1 {@code VerifyOtpRequest}, UC02). Chỉ kiểm hình thức;
 * BR-TK-05, 06 kiểm ở service. Giới hạn 8 chữ số = {@code max} của {@code otp.code_length} (V2), để chuỗi rác không
 * tới BCrypt; mã đúng định dạng nhưng sai độ dài vẫn tính là một lần sai.
 */
public record VerifyAccountRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "^\\d{1,8}$", message = "Mã OTP chỉ gồm tối đa 8 chữ số")
        String code) {

    @Override
    public String toString() {
        return "VerifyAccountRequest[email=" + email + ", code=***]";
    }
}
