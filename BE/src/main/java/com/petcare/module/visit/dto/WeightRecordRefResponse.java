package com.petcare.module.visit.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bản ghi cân nặng vừa ghi (visit-v1 schema {@code WeightRecordRef}; docs/adr/0066). Chỉ dựng bởi {@code MedicalRecordMapper}. */
public record WeightRecordRefResponse(
        @Schema(description = "Mã bản ghi cân nặng", example = "812")
        Long weightRecordId,

        @Schema(description = "Cân nặng đã ghi (kg, làm tròn 2 chữ số)", example = "12.35")
        BigDecimal weightKg,

        @Schema(description = "Thời điểm cân", example = "2026-10-22T09:30:00Z")
        Instant measuredAt) {
}
