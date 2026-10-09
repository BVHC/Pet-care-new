package com.petcare.module.branch.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotNull;

/** branch-v1 {@code TimeRange}: một khoảng giờ mở cửa, định dạng {@code HH:mm}. */
public record TimeRangeDto(
        @NotNull(message = "Thiếu giờ mở cửa")
        @JsonFormat(pattern = "HH:mm")
        LocalTime open,

        @NotNull(message = "Thiếu giờ đóng cửa")
        @JsonFormat(pattern = "HH:mm")
        LocalTime close) {
}
