package com.petcare.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * docs/api/auth-v1.md (mới, RULE-02-06 — Receptionist tạo customer tại quầy).
 * Cùng shape với {@link RegisterRequest}/{@link CreateStaffRequest} (email bắt
 * buộc + unique RULE-01-10, phone optional) — KHÔNG theo shape cũ trong
 * docs/api/iam-v1.md (phone required/email optional), vì `accounts.email` là
 * NOT NULL. Controller chỉ validate format; uniqueness (RULE-01-10) và độ dài
 * mật khẩu (RULE-01-09) là RULE-ID, validate ở Service.
 */
public record CreateCustomerRequest(
        @NotBlank @Email String email,
        // VO PhoneNumber (05#4.1) — cùng ràng buộc format như RegisterRequest.phone.
        @Pattern(regexp = "^$|^[0-9]{10}$", message = "Số điện thoại phải gồm đúng 10 chữ số") String phone,
        @NotBlank String password,
        @NotBlank String name
) {
}
