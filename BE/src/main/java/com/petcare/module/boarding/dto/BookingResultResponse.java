package com.petcare.module.boarding.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả đặt chỗ (boarding-v1 {@code BookingResult}, #8). */
public record BookingResultResponse(
        @Schema(description = "Đặt chỗ vừa tạo")
        BookingResponse booking,

        @Schema(description = "Dự kiến chưa đạt mũi bắt buộc vào ngày nhận: cảnh báo, gợi ý đặt lịch tiêm, không chặn "
                + "(BR-LT-05)")
        List<VaccineGapResponse> vaccineWarnings) {
}
