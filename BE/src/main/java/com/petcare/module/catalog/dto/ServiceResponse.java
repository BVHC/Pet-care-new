package com.petcare.module.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;

/** catalog-v1 {@code Service}; {@code kennelType} chỉ có với nhóm {@code BOARDING}. */
public record ServiceResponse(
        Long serviceId,
        String name,
        ServiceGroup group,
        MedicalType medicalType,
        long price,
        boolean priceIsFrom,
        String description,
        @JsonProperty("isActive") boolean isActive,
        KennelTypeSpec kennelType) {
}
