package com.petcare.module.inventory.dto;

import com.petcare.platform.enums.AdjustmentReason;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * docs/api/inventory-v1.md C1 — {@code POST /stores/{id}/inventory-adjustments} (AdjustInventory,
 * RULE-12-02). {@code quantityAdjusted != 0} kiểm tra ở Service (không thể diễn đạt gọn bằng
 * annotation Bean Validation chuẩn trên record), cùng RULE-ID với lý do bắt buộc.
 */
public record CreateInventoryAdjustmentRequest(
        @NotNull UUID productId,
        int quantityAdjusted,
        @NotNull AdjustmentReason reason
) {
}
