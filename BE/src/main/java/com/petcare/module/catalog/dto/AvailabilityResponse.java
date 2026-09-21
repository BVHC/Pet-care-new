package com.petcare.module.catalog.dto;

import java.util.UUID;

public record AvailabilityResponse(
        UUID storeId,
        UUID serviceId,
        boolean isActive
) {
}
