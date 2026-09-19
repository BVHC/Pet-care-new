package com.petcare.module.organization.dto;

import jakarta.validation.constraints.Size;

/**
 * docs/02-business-rules.md RULE-03-01 (UpdateOrganization). `code` không có ở
 * đây — immutable sau khi tạo (ERD UNIQUE constraint, docs/api/org-store-v1.md Q4).
 */
public record UpdateOrganizationRequest(
        @Size(max = 255) String name,
        @Size(max = 50) String taxCode,
        String address
) {
}
