package com.petcare.module.procurement.dto;

import java.util.UUID;

public record PurchaseRequestLineResponse(
        UUID productId,
        String sku,
        int requestedQuantity,
        String estimatedUnitPrice,
        String recommendedSupplierName
) {
}
