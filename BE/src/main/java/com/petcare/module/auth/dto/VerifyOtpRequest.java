package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** docs/api/openapi/auth-v1.yaml #VerifyOtpRequest (sửa 2026-09-13 — email thay phone). */
public record VerifyOtpRequest(
        @NotBlank @Email String email,
        @NotBlank String otpCode
) {
}
