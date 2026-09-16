package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** C7 ResetPassword — đổi mật khẩu bằng OTP đã gửi ở bước ForgotPassword. */
public record ResetPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank String otpCode,
        @NotBlank String newPassword
) {
}
