package com.petcare.module.report.dto;

import java.time.Instant;

import com.petcare.module.sales.api.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code LostRevenueRow}: Một Order hủy vì khách không thanh toán (BR-BC-02, BR-BH-05). */
public record LostRevenueRow(
        @Schema(description = "Mã Order", example = "501")
        Long orderId,
        @Schema(description = "Mã chứng từ Order", example = "DH-20261015-0007")
        String orderCode,
        @Schema(description = "Chi nhánh của Order", example = "2")
        Long branchId,
        @Schema(description = "Nguồn Order", example = "VISIT")
        OrderSource source,
        @Schema(description = "Giá trị Order (VND)", example = "450000")
        long amount,
        @Schema(description = "Lý do hủy", example = "Khách khám xong bỏ về")
        String reason,
        @Schema(description = "Hủy lúc", example = "2026-10-15T09:30:00Z")
        Instant cancelledAt) {
}
