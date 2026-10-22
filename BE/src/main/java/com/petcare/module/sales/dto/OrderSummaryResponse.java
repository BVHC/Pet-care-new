package com.petcare.module.sales.dto;

import java.time.Instant;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.entity.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một dòng của danh sách Order (sales-v1 {@code OrderSummary}). */
public record OrderSummaryResponse(
        @Schema(description = "Mã Order", example = "101")
        Long orderId,

        @Schema(description = "Số Order liên tục theo tháng", example = "DH-2610-00001")
        String code,

        @Schema(description = "Nguồn Order", example = "RETAIL")
        OrderSource source,

        @Schema(description = "Trạng thái", example = "PENDING")
        OrderStatus status,

        @Schema(description = "Khách hàng giữ tại thời điểm tạo", example = "5")
        Long customerId,

        @Schema(description = "Họ tên khách", example = "Nguyễn Văn An")
        String customerName,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Tổng tiền các dòng, VND", example = "150000")
        long totalAmount,

        @Schema(description = "Thời điểm tạo (UTC)", example = "2026-10-19T03:00:00Z")
        Instant createdAt,

        @Schema(description = "Thời điểm chốt chờ thu tiền (UTC)", example = "2026-10-19T04:00:00Z")
        Instant pendingAt,

        @Schema(description = "Thời điểm thu tiền (UTC)", example = "2026-10-19T04:10:00Z")
        Instant paidAt) {
}
