package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * docs/api/openapi/auth-v1.yaml #RegisterRequest (sửa 2026-09-13 — email
 * bắt buộc thay phone, RULE-01-10). Controller chỉ validate format
 * (docs/convention/backend/06-validation.md) — độ dài password (RULE-01-09)
 * và uniqueness (RULE-01-10) là RULE-ID, validate ở Service.
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        // VO PhoneNumber (05#4.1) / openapi Phone schema = CONFIRMED 10 chữ số.
        // phone optional (RULE-01-10) nên cho phép "" hoặc null; nếu có giá trị thì
        // phải đúng 10 chữ số — trước đây không có @Pattern nên phone sai định dạng
        // lọt qua Bean Validation, chỉ bị chặn nếu trùng (existsByPhone).
        @Pattern(regexp = "^$|^[0-9]{10}$", message = "Số điện thoại phải gồm đúng 10 chữ số") String phone,
        @NotBlank String password,
        @NotBlank String name
) {
}
