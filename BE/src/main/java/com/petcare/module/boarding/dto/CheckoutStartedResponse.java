package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả bắt đầu trả thú / kết thúc do thú mất (boarding-v1 {@code CheckoutStarted}, #16, #18). */
public record CheckoutStartedResponse(
        @Schema(description = "Đặt chỗ lưu trú sau khi xử lý")
        BookingResponse booking,

        @Schema(description = "Order lưu trú PENDING (hoặc PAID nếu thú mất sau khi đã thu tiền)", example = "501")
        Long orderId,

        @Schema(description = "Số đêm tính tiền, tối thiểu 1; trả sau giờ trả quy định cộng 1 đêm", example = "3")
        int nights,

        @Schema(description = "Tổng tiền Order lưu trú, VND", example = "450000")
        long amount) {
}
