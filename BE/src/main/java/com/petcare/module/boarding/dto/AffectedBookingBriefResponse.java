package com.petcare.module.boarding.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Đặt chỗ thiếu chỗ sau khi giảm sức chứa (boarding-v1 {@code AffectedBookingBrief}). */
public record AffectedBookingBriefResponse(
        @Schema(description = "Mã đặt chỗ", example = "88")
        Long bookingId,

        @Schema(description = "Mã hiển thị của đặt chỗ", example = "LT-2610-00012")
        String code,

        @Schema(description = "Ngày nhận dự kiến", example = "2026-11-02")
        LocalDate checkInDate,

        @Schema(description = "Ngày trả dự kiến", example = "2026-11-05")
        LocalDate checkOutDate,

        @Schema(description = "Tên thú cưng", example = "Milu")
        String petName) {
}
