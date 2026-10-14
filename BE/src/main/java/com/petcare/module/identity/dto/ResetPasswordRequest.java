package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Email tài khoản", example = "lan.nguyen@gmail.com")
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email,

        @Schema(description = "Mã OTP đã gửi tới email", example = "482913", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mã OTP không được để trống")
        @Pattern(regexp = "^\\d{1,8}$", message = "Mã OTP chỉ gồm tối đa 8 chữ số")
        String code,

        @Schema(description = "Mật khẩu mới (BR-TK-03)",
                example = "MatKhauMoi456",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "Mật khẩu mới không được để trống")
        String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[email=" + email + ", code=***, newPassword=***]";
    }
}
