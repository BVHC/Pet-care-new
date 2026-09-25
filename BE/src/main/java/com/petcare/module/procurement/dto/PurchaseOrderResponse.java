package com.petcare.module.procurement.dto;

import com.petcare.platform.enums.PurchaseOrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
        UUID orderId,
        String poNumber,
        UUID purchaseRequestId,
        UUID storeId,
        UUID supplierId,
        String supplierName,
        PurchaseOrderStatus status,
        String totalAmount,
        UUID createdBy,
        LocalDateTime createdAt,
        List<PurchaseOrderLineResponse> lines
) {
}
