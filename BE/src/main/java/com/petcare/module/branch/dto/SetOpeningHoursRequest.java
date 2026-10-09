package com.petcare.module.branch.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code PUT /api/branches/{id}/opening-hours} (branch-v1 {@code SetOpeningHoursRequest}, UC14). Gửi đủ 7
 * ngày của một phiên bản (A5). {@code cancelAffected = true} cho phép hủy hàng loạt lịch hẹn / đặt chỗ bị ảnh
 * hưởng (BR-CN-04); mặc định {@code false}.
 */
public record SetOpeningHoursRequest(
        @NotNull(message = "Thiếu ngày hiệu lực")
        LocalDate effectiveFrom,

        @NotNull(message = "Thiếu giờ mở cửa các ngày trong tuần")
        List<@Valid @NotNull(message = "Ngày không được để trống") DayHoursDto> days,

        Boolean cancelAffected) {
}
