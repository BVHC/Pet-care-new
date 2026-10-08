package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/password/reset} (identity-v1 {@code ResetPasswordRequest}, UC04). Độ dài mã là [CFG]
 * {@code otp.code_length} nên chỉ chặn hình thức (tối đa 8 chữ số, như {@link VerifyAccountRequest}). Chính sách mật
 * khẩu mới (BR-TK-03: độ dài [CFG], chữ và số, ≤ 72 byte) kiểm ở service, không bằng annotation (convention 06,
 * docs/adr/0019).
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "^\\d{1,8}$", message = "Mã OTP chỉ gồm tối đa 8 chữ số")
        String code,

        @NotBlank(message = "Mật khẩu mới không được để trống")
        String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[email=" + email + ", code=***, newPassword=***]";
    }
}
