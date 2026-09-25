package com.petcare.module.procurement.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * docs/api/procurement-v1.md C2 — POST /purchase-orders (CreatePurchaseOrder, RULE-13-04). Lines
 * KHÔNG nhận từ client — copy 1:1 từ PurchaseRequest đã APPROVED (đơn giản, chống giả mạo).
 */
public record CreatePurchaseOrderRequest(
        @NotNull UUID purchaseRequestId,
        @NotNull UUID supplierId
) {
}
