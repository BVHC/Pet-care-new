package com.petcare.module.organization.dto;

import com.petcare.platform.enums.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * docs/api/openapi/org-store-v1.yaml #CreateStoreResourceRequest — `POST /stores/{id}/resources`
 * (RULE-03-02/08). `resourceCode` unique trong Store (ERD UK `(store_id, resource_code)`),
 * validate ở Service. `isActive` mặc định `true` khi không gửi (khớp openapi `default: true`).
 */
public record CreateStoreResourceRequest(
        @NotBlank @Size(max = 50) String resourceCode,
        @NotBlank @Size(max = 100) String resourceName,
        @NotNull ResourceType resourceType,
        Boolean isActive
) {
}
