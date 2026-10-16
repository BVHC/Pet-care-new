package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import com.petcare.module.inventory.entity.StockReceiptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một dòng danh sách phiếu nhập (inventory-v1 {@code StockReceiptSummary}). {@code totalCost} = Σ số lượng × giá nhập. */
public record StockReceiptSummaryResponse(
        @Schema(description = "Mã phiếu", example = "21")
        Long receiptId,

        @Schema(description = "Số phiếu", example = "PN-2610-00001")
        String code,

        @Schema(description = "Tên nhà cung cấp", example = "Công ty Thú y Minh Tâm")
        String supplierName,

        @Schema(description = "Ngày nhập", example = "2026-10-16")
        LocalDate receiptDate,

        @Schema(description = "Trạng thái", example = "CONFIRMED")
        StockReceiptStatus status,

        @Schema(description = "Số dòng", example = "3")
        int lineCount,

        @Schema(description = "Tổng giá trị nhập (VND)", example = "4250000")
        long totalCost) {
}
