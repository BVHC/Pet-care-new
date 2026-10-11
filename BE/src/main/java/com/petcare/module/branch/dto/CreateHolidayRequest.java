package com.petcare.module.branch.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/branches/{id}/holidays} (branch-v1 {@code CreateHolidayRequest}, UC14).
 * {@code cancelAffected = true} cho phép hủy hàng loạt lịch bị ảnh hưởng (BR-CN-03, BR-LH-10); mặc định {@code false}.
 */
public record CreateHolidayRequest(
        @NotNull(message = "Thiếu ngày nghỉ")
        LocalDate holidayDate,

        @Size(max = 200, message = "Lý do tối đa 200 ký tự")
        String reason,

        Boolean cancelAffected) {
}
