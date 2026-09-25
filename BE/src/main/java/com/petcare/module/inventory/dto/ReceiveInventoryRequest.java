package com.petcare.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * docs/api/inventory-v1.md C1 — {@code POST /stores/{id}/inventory/receive} (ReceiveInventory).
 * {@code batchNumber} bắt buộc (RULE-12-11 — "toàn bộ biến động hàng hóa... phải được quản lý
 * theo số lô", không phải tùy chọn như bản nháp contract trước đó). {@code manufactureDate}/
 * {@code expiryDate} tùy chọn — hàng ACCESSORY hợp lệ không có hạn dùng, không tự bịa ngày.
 */
public record ReceiveInventoryRequest(
        @NotNull UUID productId,
        @Positive int quantity,
        @NotBlank @Size(max = 100) String batchNumber,
        LocalDate manufactureDate,
        LocalDate expiryDate
) {
}
