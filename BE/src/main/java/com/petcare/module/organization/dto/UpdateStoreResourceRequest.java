package com.petcare.module.organization.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * docs/api/openapi/org-store-v1.yaml #UpdateStoreResourceRequest — `PATCH
 * /stores/{id}/resources/{rid}`. `resourceCode` bất biến (ASSUMPTION A4, UK immutable) nên
 * không có field này. Field null = giữ nguyên (partial update).
 */
public record UpdateStoreResourceRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "resourceName không được để trống") String resourceName,
        Boolean isActive
) {
}
