package com.petcare.module.order.dto;

import java.util.UUID;

public record OrderItemResponse(
        UUID productId,
        String sku,
        int quantity,
        String unitPrice,
        String lineTotal
) {
}
