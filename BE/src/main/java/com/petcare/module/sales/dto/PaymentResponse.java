package com.petcare.module.sales.dto;

import java.time.Instant;
import java.util.List;

import com.petcare.module.sales.entity.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phiếu thu (sales-v1 {@code Payment}, #14, #15). {@code orders} là các Order đã chuyển PAID trong phiếu. */
public record PaymentResponse(
        @Schema(description = "Mã phiếu thu", example = "55")
        Long paymentId,

        @Schema(description = "Số phiếu thu liên tục theo tháng", example = "PT-2610-00001")
        String code,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Khách", example = "5")
        Long customerId,

        @Schema(description = "Ca thu ngân ghi nhận phiếu", example = "12")
        Long cashierShiftId,

        @Schema(description = "Phương thức", example = "CASH")
        PaymentMethod method,

        @Schema(description = "Số tiền, VND", example = "350000")
        long amount,

        @Schema(description = "Lễ tân thu", example = "7")
        Long receivedBy,

        @Schema(description = "Thời điểm thu (UTC)", example = "2026-10-21T03:00:00Z")
        Instant paidAt,

        @Schema(description = "Các Order trong phiếu")
        List<OrderSummaryResponse> orders) {
}
