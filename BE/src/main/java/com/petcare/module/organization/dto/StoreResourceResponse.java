package com.petcare.module.organization.dto;

import com.petcare.platform.enums.ResourceType;

import java.util.UUID;

public record StoreResourceResponse(
        UUID resourceId,
        UUID storeId,
        String resourceCode,
        String resourceName,
        ResourceType resourceType,
        boolean isActive
) {
}
