package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * docs/api/openapi/auth-v1.yaml #LoginRequest — email là danh tính đăng
 * nhập duy nhất (RULE-01-10). Controller chỉ validate format
 * (docs/convention/backend/06-validation.md) — tồn tại/mật khẩu đúng
 * (RULE-01-01) là RULE-ID, validate ở Service.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
