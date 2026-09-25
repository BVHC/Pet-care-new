package com.petcare.module.inventory.dto;

import java.util.UUID;

/** {@code lowStock} = {@code quantityAvailable <= minStockLevel}, tính ở Service (TriggerLowStockAlert). */
public record InventoryItemResponse(
        UUID storeId,
        UUID productId,
        String sku,
        int quantityPhysical,
        int quantityReserved,
        int quantityAvailable,
        int minStockLevel,
        boolean lowStock
) {
}
