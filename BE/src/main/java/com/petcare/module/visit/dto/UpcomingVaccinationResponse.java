package com.petcare.module.visit.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một mũi tái chủng sắp tới (visit-v1 schema {@code UpcomingVaccination}, BR-KH-07; docs/adr/0108). */
public record UpcomingVaccinationResponse(
        @Schema(description = "Loại vaccine", example = "3")
        Long vaccineTypeId,
        @Schema(description = "Tên loại vaccine", example = "Dại")
        String vaccineTypeName,
        @Schema(description = "Ngày tái chủng kế tiếp (BR-KB-04)", example = "2027-10-22")
        LocalDate nextDueDate) {
}
