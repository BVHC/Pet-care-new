package com.petcare.module.procurement.dto;

import java.util.UUID;

public record PurchaseOrderLineResponse(
        UUID productId,
        String sku,
        int orderedQuantity,
        int receivedQuantity,
        String unitPrice
) {
}
