package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body của {@code POST /api/branches/{id}/opening-hours/impact}: cùng nội dung như đặt giờ, không ghi gì (A1). */
public record OpeningHoursImpactRequest(
        @NotNull(message = "Thiếu ngày hiệu lực")
        LocalDate effectiveFrom,

        @NotNull(message = "Thiếu giờ mở cửa các ngày trong tuần")
        List<@Valid @NotNull(message = "Ngày không được để trống") DayHoursDto> days) {
}
