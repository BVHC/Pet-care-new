package com.petcare.module.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * docs/02-business-rules.md RULE-03-01 (CreateOrganization). Controller chỉ
 * validate format; unique code (RULE-03-01) validate ở Service.
 */
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 50) String taxCode,
        String address
) {
}
