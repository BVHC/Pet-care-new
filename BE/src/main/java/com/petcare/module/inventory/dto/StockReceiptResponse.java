package com.petcare.module.inventory.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.petcare.module.inventory.entity.StockReceiptStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Phiếu nhập kho (inventory-v1 {@code StockReceipt}, UC74). */
public record StockReceiptResponse(
        @Schema(description = "Mã phiếu", example = "21")
        Long receiptId,

        @Schema(description = "Số phiếu liên tục theo tháng", example = "PN-2610-00001")
        String code,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Nhà cung cấp", example = "3")
        Long supplierId,

        @Schema(description = "Tên nhà cung cấp", example = "Công ty Thú y Minh Tâm")
        String supplierName,

        @Schema(description = "Ngày nhập", example = "2026-10-16")
        LocalDate receiptDate,

        @Schema(description = "Trạng thái", example = "DRAFT")
        StockReceiptStatus status,

        @Schema(description = "Ghi chú", example = "Giao buổi sáng")
        String note,

        @Schema(description = "Các dòng phiếu")
        List<ReceiptLineResponse> lines,

        @Schema(description = "Người tạo", example = "7")
        Long createdBy,

        @Schema(description = "Người xác nhận", example = "7")
        Long confirmedBy,

        @Schema(description = "Thời điểm xác nhận (UTC)", example = "2026-10-16T03:00:00Z")
        Instant confirmedAt,

        @Schema(description = "Người hủy", example = "7")
        Long cancelledBy,

        @Schema(description = "Thời điểm hủy (UTC)", example = "2026-10-16T04:00:00Z")
        Instant cancelledAt,

        @Schema(description = "Lý do hủy", example = "Nhập nhầm nhà cung cấp")
        String cancelReason) {
}
