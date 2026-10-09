package com.petcare.module.branch.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/** Body của {@code POST /api/branches/{id}/holidays/impact}: xem trước, không ghi gì (A1). */
public record HolidayImpactRequest(
        @NotNull(message = "Thiếu ngày nghỉ")
        LocalDate holidayDate) {
}
