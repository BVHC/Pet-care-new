package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ResourceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * docs/api/openapi/catalog-v1.yaml #RequiredResource — khai báo cùng lúc tạo/sửa Service
 * (contract ASSUMPTION A1), replace-as-whole trên PATCH.
 */
public record RequiredResourceItem(
        @NotNull ResourceType resourceType,
        @Min(1) Integer quantityRequired
) {
}
