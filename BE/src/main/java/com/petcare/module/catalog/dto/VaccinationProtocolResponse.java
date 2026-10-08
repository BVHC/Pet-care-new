package com.petcare.module.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.customer.api.Species;

/** catalog-v1 {@code VaccinationProtocol}. */
public record VaccinationProtocolResponse(
        Long protocolId,
        Species species,
        Long vaccineTypeId,
        int doseNumber,
        int intervalDays,
        int minAgeWeeks,
        boolean requiredForBoarding,
        @JsonProperty("isActive") boolean isActive) {
}
