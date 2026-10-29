package com.petcare.module.boarding.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Điều kiện nhận thú (boarding-v1 {@code CheckInReadiness}, #13). */
public record CheckInReadinessResponse(
        @Schema(description = "Loại vaccine bắt buộc khi lưu trú mà thú chưa đạt vào hôm nay (BR-LT-05); rỗng = đạt")
        List<VaccineGapResponse> vaccineGaps,

        @Schema(description = "Chuồng AVAILABLE đúng loại đã đặt, theo mã (BR-LT-08)")
        List<KennelResponse> availableKennels,

        @Schema(description = "Giờ hiện tại nằm trong giờ mở cửa của chi nhánh (BR-LT-04)", example = "true")
        boolean withinOpeningHours,

        @Schema(description = "Nhận được ngay: đủ mũi, còn chuồng, trong giờ, chưa quá ngày nhận, và (nếu nhận sớm) "
                + "còn chỗ các đêm thêm", example = "true")
        boolean canCheckIn) {
}
