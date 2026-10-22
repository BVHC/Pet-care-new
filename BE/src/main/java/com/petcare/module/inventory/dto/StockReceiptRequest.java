package com.petcare.module.inventory.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body của {@code POST} / {@code PUT /api/stock-receipts} (inventory-v1 {@code StockReceiptRequest}, UC74). PUT thay toàn bộ (A2). */
public record StockReceiptRequest(
        @Schema(description = "Nhà cung cấp (phải đang hợp tác)", example = "3")
        @NotNull(message = "Vui lòng chọn nhà cung cấp")
        Long supplierId,

        @Schema(description = "Ngày nhập; không ở tương lai khi xác nhận", example = "2026-10-16")
        @NotNull(message = "Vui lòng nhập ngày nhập")
        LocalDate receiptDate,

        @Schema(description = "Ghi chú", example = "Giao buổi sáng")
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        String note,

        @Schema(description = "Các dòng phiếu, 1–200 dòng")
        @NotNull(message = "Phiếu cần ít nhất một dòng")
        @Size(min = 1, max = 200, message = "Phiếu cần từ 1 đến 200 dòng")
        @Valid
        List<ReceiptLineRequest> lines) {
}
