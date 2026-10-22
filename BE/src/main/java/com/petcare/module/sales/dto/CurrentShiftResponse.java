package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Ca đang mở của tôi (sales-v1 {@code CurrentShift}, #17): {@code shift} chỉ có khi {@code open = true}. */
public record CurrentShiftResponse(
        @Schema(description = "Có ca OPEN không", example = "true")
        boolean open,

        @Schema(description = "Ca đang mở")
        CashierShiftResponse shift) {
}
