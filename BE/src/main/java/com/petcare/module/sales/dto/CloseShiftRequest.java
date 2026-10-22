package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Chốt ca thu ngân (sales-v1 {@code CloseShiftRequest}, #18). Chỉ đếm tiền mặt; chuyển khoản lễ tân đã xác nhận từng phiếu. */
public record CloseShiftRequest(
        @Schema(description = "Tiền mặt thực đếm, VND", example = "480000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập số tiền mặt thực đếm")
        @PositiveOrZero(message = "Số tiền thực đếm không được âm")
        Long countedCash) {
}
