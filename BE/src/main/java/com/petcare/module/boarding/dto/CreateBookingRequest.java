package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Đặt chỗ lưu trú (boarding-v1 {@code CreateBookingRequest}, #8). */
public record CreateBookingRequest(
        @Schema(description = "Thú cưng (khách: thú của mình)", example = "21",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn thú cưng")
        Long petId,

        @Schema(description = "Chi nhánh", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn chi nhánh")
        Long branchId,

        @Schema(description = "Loại chuồng đang bật tại chi nhánh", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn loại chuồng")
        Long kennelTypeId,

        @Schema(description = "Ngày nhận dự kiến", example = "2026-11-02", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ngày nhận")
        LocalDate checkInDate,

        @Schema(description = "Ngày trả dự kiến", example = "2026-11-05", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ngày trả")
        LocalDate checkOutDate) {
}
