package com.petcare.module.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/auth/register/resend-otp} (identity-v1 {@code EmailRequest}, UC02). Chỉ kiểm hình thức;
 * BR-TK-07 kiểm ở service.
 */
public record ResendRegistrationOtpRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email tối đa 255 ký tự")
        String email) {
}
