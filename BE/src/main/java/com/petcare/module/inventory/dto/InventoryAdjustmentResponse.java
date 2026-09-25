package com.petcare.module.inventory.dto;

import com.petcare.platform.enums.AdjustmentReason;
import com.petcare.platform.enums.InventoryAdjustmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record InventoryAdjustmentResponse(
        UUID adjustmentId,
        UUID storeId,
        UUID productId,
        int quantityAdjusted,
        AdjustmentReason reason,
        InventoryAdjustmentStatus status,
        UUID createdBy,
        UUID approvedBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
