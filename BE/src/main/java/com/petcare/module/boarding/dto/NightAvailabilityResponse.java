package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một đêm của khoảng lưu trú (boarding-v1 {@code NightAvailability}). */
public record NightAvailabilityResponse(
        @Schema(description = "Đêm bắt đầu từ ngày này", example = "2026-11-02")
        LocalDate date,

        @Schema(description = "Sức chứa: số chuồng không bảo trì (BR-LT-01)", example = "4")
        int capacity,

        @Schema(description = "Số đặt chỗ đang chiếm chỗ đêm này (BR-LT-03)", example = "3")
        int occupied,

        @Schema(description = "Số chỗ còn lại, không âm", example = "1")
        int remaining) {
}
