package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Loại vaccine bắt buộc khi lưu trú mà thú chưa đạt (boarding-v1 {@code VaccineGap}, BR-LT-05). */
public record VaccineGapResponse(
        @Schema(description = "Loại vaccine", example = "3")
        Long vaccineTypeId,

        @Schema(description = "Tên loại vaccine", example = "Dại")
        String vaccineTypeName,

        @Schema(description = "Ngày tiêm gần nhất tại hệ thống; null nếu chưa tiêm", example = "2025-10-01")
        LocalDate lastAdministeredOn,

        @Schema(description = "Ngày tái chủng; null nếu chưa tiêm", example = "2026-10-01")
        LocalDate nextDueDate) {
}
