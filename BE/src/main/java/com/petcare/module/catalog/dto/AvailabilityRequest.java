package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.NotNull;

/** docs/api/openapi/catalog-v1.yaml #AvailabilityRequest — {@code PUT .../availability} (RULE-05-04). */
public record AvailabilityRequest(
        @NotNull Boolean isActive
) {
}
