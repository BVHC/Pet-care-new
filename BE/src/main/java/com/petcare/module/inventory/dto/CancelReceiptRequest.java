package com.petcare.module.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Body (tùy chọn) của {@code POST /api/stock-receipts/{id}/cancel} (inventory-v1 {@code CancelReceiptRequest}); lý do không bắt buộc (A3). */
public record CancelReceiptRequest(
        @Schema(description = "Lý do hủy", example = "Nhập nhầm nhà cung cấp")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
