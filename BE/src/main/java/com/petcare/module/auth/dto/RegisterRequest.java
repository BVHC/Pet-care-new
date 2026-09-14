package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * docs/api/openapi/auth-v1.yaml #RegisterRequest (sửa 2026-09-13 — email
 * bắt buộc thay phone, RULE-01-10). Controller chỉ validate format
 * (docs/convention/backend/06-validation.md) — độ dài password (RULE-01-09)
 * và uniqueness (RULE-01-10) là RULE-ID, validate ở Service.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        String phone,
        @NotBlank String password,
        @NotBlank String name
) {
}
