package com.petcare.module.procurement.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * docs/api/procurement-v1.md C1 — POST /stores/{id}/purchase-requests (CreatePurchaseRequest,
 * RULE-13-01). {@code storeId} không nằm trong body — lấy từ path (cùng kiểu ReceiveInventoryRequest
 * ở Module 12, storeId đã có trong URL).
 */
public record CreatePurchaseRequestRequest(
        @NotEmpty @Valid List<PurchaseRequestLineItem> lines
) {
}
