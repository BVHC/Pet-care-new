package com.petcare.module.auth.dto;

import com.petcare.platform.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * docs/api/auth-v1.md C7 (POST /staff-accounts, D-04). Theo bản .md (mới hơn,
 * 2026-09-13): email required + unique (RULE-01-10), phone optional — khác
 * openapi/auth-v1.yaml hiện còn ghi phone required (chưa đồng bộ, xem plan
 * mục H). Controller chỉ validate format; role/scope hợp lệ (RULE-02-01/02/03)
 * và uniqueness (RULE-01-10) là RULE-ID, validate ở Service.
 */
public record CreateStaffRequest(
        @NotBlank @Email String email,
        String phone,
        @NotBlank String password,
        @NotBlank String name,
        @NotNull UserRole role,
        UUID organizationId,
        UUID storeId
) {
}
