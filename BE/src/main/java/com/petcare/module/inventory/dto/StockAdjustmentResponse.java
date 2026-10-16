package com.petcare.module.inventory.dto;

import java.time.Instant;
import java.util.List;

import com.petcare.module.inventory.entity.StockAdjustmentReason;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phiếu điều chỉnh tồn (inventory-v1 {@code StockAdjustment}, UC76): hiệu lực ngay khi lưu. */
public record StockAdjustmentResponse(
        @Schema(description = "Mã phiếu", example = "9")
        Long adjustmentId,

        @Schema(description = "Số phiếu liên tục theo tháng", example = "DC-2610-00001")
        String code,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Lý do", example = "DAMAGED")
        StockAdjustmentReason reason,

        @Schema(description = "Ghi chú", example = "Rơi vỡ khi vận chuyển")
        String note,

        @Schema(description = "Các dòng điều chỉnh")
        List<AdjustmentLineResponse> lines,

        @Schema(description = "Người lập", example = "7")
        Long createdBy,

        @Schema(description = "Thời điểm lập (UTC)", example = "2026-10-16T03:00:00Z")
        Instant createdAt) {
}
