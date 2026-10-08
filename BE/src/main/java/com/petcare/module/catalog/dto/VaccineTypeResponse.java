package com.petcare.module.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.customer.api.Species;

/** catalog-v1 {@code VaccineType}. */
public record VaccineTypeResponse(
        Long vaccineTypeId,
        String name,
        Species species,
        @JsonProperty("isActive") boolean isActive) {
}
