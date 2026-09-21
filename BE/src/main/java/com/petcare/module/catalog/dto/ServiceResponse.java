package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ServiceCategory;

import java.util.List;
import java.util.UUID;

public record ServiceResponse(
        UUID serviceId,
        UUID organizationId,
        String code,
        String name,
        ServiceCategory category,
        String basePrice,
        int durationMinutes,
        boolean isActive,
        List<RequiredResourceItem> requiredResources
) {
}
