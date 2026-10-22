package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Gia hạn ngày trả (boarding-v1 {@code ExtendBookingRequest}, #11). */
public record ExtendBookingRequest(
        @Schema(description = "Ngày trả dự kiến mới, sau ngày trả hiện tại (BR-LT-06)", example = "2026-11-07",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ngày trả mới")
        LocalDate checkOutDate) {
}
