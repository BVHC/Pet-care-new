package com.petcare.module.organization.dto;

import com.petcare.platform.enums.FacilityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * docs/api/org-store-v1.md C2 — `POST /organizations/{id}/stores` (RULE-03-01,
 * FSM-2 `[*] -> DRAFT`). Controller chỉ validate format; unique
 * `(organizationId, code)` (RULE-03-01) validate ở Service. Khác
 * `RegisterRequest.phone` (optional): `phone` ở đây bắt buộc (ERD `stores.phone
 * NOT NULL`), nên không có nhánh `^$|...`.
 */
public record CreateStoreRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @NotNull FacilityType facilityType,
        @NotBlank String address,
        @NotBlank @Pattern(regexp = "^[0-9]{10}$", message = "Số điện thoại phải gồm đúng 10 chữ số") String phone
) {
}
