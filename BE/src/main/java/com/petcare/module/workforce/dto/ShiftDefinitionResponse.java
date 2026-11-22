package com.petcare.module.workforce.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;

/** Ca làm việc (workforce-v1 {@code ShiftDefinition}). Không trả {@code createdAt} / {@code updatedAt}. */
public record ShiftDefinitionResponse(
        @Schema(description = "Mã ca", example = "7")
        Long shiftDefinitionId,

        @Schema(description = "Chi nhánh của ca", example = "1")
        Long branchId,

        @Schema(description = "Tên ca", example = "Ca sáng")
        String name,

        @Schema(description = "Giờ bắt đầu, HH:mm", example = "08:00", type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,

        @Schema(description = "Giờ kết thúc, HH:mm", example = "12:00", type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,

        @Schema(description = "true = đang dùng; false = đã ngừng dùng", example = "true")
        boolean active) {
}
