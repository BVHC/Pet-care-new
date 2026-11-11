package com.petcare.module.ai.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code VaccinationItem}: một loại vaccine trong phác đồ của loài, tính từ mũi mới nhất chưa bị thay thế (BR-AI-11). */
public record VaccinationItem(
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED) Long vaccineTypeId,

        @Schema(example = "Dại", requiredMode = Schema.RequiredMode.REQUIRED) String vaccineName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) VaccinationStatus status,

        @Schema(example = "1", nullable = true) Integer lastDoseNumber,

        @Schema(example = "2026-10-16", nullable = true) LocalDate lastAdministeredOn,

        @Schema(example = "2", nullable = true) Integer nextDoseNumber,

        @Schema(example = "2026-11-12", nullable = true) LocalDate nextDueDate,

        @Schema(description = "Tuổi tối thiểu (tuần) của mũi 1, có với NOT_YET_ELIGIBLE / NOT_STARTED", example = "12",
                nullable = true)
        Integer firstDoseFromAgeWeeks) {
}
