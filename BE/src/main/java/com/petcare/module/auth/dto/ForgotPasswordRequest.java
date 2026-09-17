package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** C8 ForgotPassword — gửi OTP mục đích PASSWORD_RESET tới email. */
public record ForgotPasswordRequest(
        @NotBlank @Email String email
) {
}
