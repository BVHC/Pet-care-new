package com.petcare.module.boarding.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Còn chỗ theo đêm của một loại chuồng (boarding-v1 {@code BoardingAvailability}, #7). */
public record BoardingAvailabilityResponse(
        @Schema(description = "Loại chuồng", example = "5")
        Long kennelTypeId,

        @Schema(description = "Giá một đêm hiện hành (VND); đặt chỗ sẽ snapshot giá này", example = "150000")
        long nightlyPrice,

        @Schema(description = "Từng đêm từ ngày nhận tới trước ngày trả")
        List<NightAvailabilityResponse> nights,

        @Schema(description = "true nếu mọi đêm còn ít nhất một chỗ", example = "true")
        boolean available) {
}
