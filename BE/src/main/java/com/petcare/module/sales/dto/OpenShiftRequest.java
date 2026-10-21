package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Mở ca thu ngân (sales-v1 {@code OpenShiftRequest}, #16). */
public record OpenShiftRequest(
        @Schema(description = "false = ca thường, true = ca ngoài giờ (BR-TG-05)", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn loại ca")
        Boolean afterHours) {
}
