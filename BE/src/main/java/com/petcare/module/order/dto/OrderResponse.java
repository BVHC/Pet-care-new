package com.petcare.module.order.dto;

import com.petcare.platform.enums.OrderChannel;
import com.petcare.platform.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String orderNumber,
        UUID storeId,
        UUID customerId,
        OrderChannel channel,
        OrderStatus status,
        List<OrderItemResponse> items,
        String subtotal,
        String discountAmount,
        String totalAmount,
        String totalRefundedAmount,
        LocalDateTime reservedUntil,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt
) {
}
