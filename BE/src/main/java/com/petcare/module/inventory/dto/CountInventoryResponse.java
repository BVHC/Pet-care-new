package com.petcare.module.inventory.dto;

import java.util.UUID;

/**
 * {@code adjustmentId} null khi {@code variance == 0} (kiểm kê khớp — không sinh phiếu, không
 * lưu gì thêm, docs/api/inventory-v1.md §E Q5 DECIDED).
 */
public record CountInventoryResponse(
        UUID storeId,
        UUID productId,
        int countedQuantity,
        int variance,
        UUID adjustmentId
) {
}
