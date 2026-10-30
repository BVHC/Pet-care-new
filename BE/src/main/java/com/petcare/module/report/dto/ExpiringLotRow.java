package com.petcare.module.report.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code ExpiringLotRow}: Lô còn tồn sắp hoặc đã hết hạn (BR-KO-07). */
public record ExpiringLotRow(
        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,
        @Schema(description = "Mã sản phẩm", example = "15")
        Long productId,
        @Schema(description = "Tên sản phẩm", example = "Vaccine dại")
        String productName,
        @Schema(description = "Mã lô", example = "88")
        Long stockLotId,
        @Schema(description = "Số lô nhà sản xuất", example = "LOT-2026-09", nullable = true)
        String lotNumber,
        @Schema(description = "Hạn dùng", example = "2026-11-05")
        LocalDate expiryDate,
        @Schema(description = "Số lượng còn trong lô", example = "6")
        int quantity,
        @Schema(description = "Đã hết hạn (hạn dùng trước hôm nay)", example = "false")
        boolean expired) {
}
