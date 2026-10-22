package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/password/forgot} (identity-v1 {@code EmailRequest}, UC04). Gửi lại mã = gọi lại endpoint này
 * (identity-v1 A7).
 */
public record ForgotPasswordRequest(
        @Schema(description = "Email tài khoản cần đặt lại mật khẩu", example = "lan.nguyen@gmail.com")
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email) {
}
