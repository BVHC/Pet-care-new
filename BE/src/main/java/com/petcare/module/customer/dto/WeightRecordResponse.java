package com.petcare.module.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.petcare.module.customer.api.WeightSource;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một lần cân (customer-v1 {@code WeightRecord}, BR-KH-04). {@code source = OWNER}: chủ tự khai. */
public record WeightRecordResponse(
        @Schema(description = "Mã bản ghi cân nặng", example = "501")
        Long weightRecordId,

        @Schema(description = "Cân nặng (kg)", example = "8.5")
        BigDecimal weightKg,

        @Schema(description = "Nguồn: VET khám, INTAKE nhận lưu trú, OWNER chủ tự khai", example = "OWNER")
        WeightSource source,

        @Schema(description = "Lượt khám (nguồn VET)", example = "1204")
        Long visitId,

        @Schema(description = "Đặt chỗ lưu trú (nguồn INTAKE)", example = "77")
        Long boardingBookingId,

        @Schema(description = "Thời điểm cân (UTC)", example = "2026-10-16T03:00:00Z")
        Instant measuredAt) {
}
