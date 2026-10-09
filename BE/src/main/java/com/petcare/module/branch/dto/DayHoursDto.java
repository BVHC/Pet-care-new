package com.petcare.module.branch.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * branch-v1 {@code DayHours}: giờ mở cửa của một thứ (1 = thứ Hai … 7 = Chủ nhật). Danh sách rỗng là ngày nghỉ cố
 * định hằng tuần. Số khoảng (tối đa 2) và thứ tự giờ kiểm ở service để trả {@code BR-CN-02}.
 */
public record DayHoursDto(
        @NotNull(message = "Thiếu thứ trong tuần")
        Integer dayOfWeek,

        @NotNull(message = "Thiếu danh sách khoảng giờ")
        List<@Valid @NotNull(message = "Khoảng giờ không được để trống") TimeRangeDto> ranges) {
}
