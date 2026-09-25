package com.petcare.module.order.dto;

import com.petcare.platform.enums.OrderChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * docs/api/order-v1.md C1 — POST /orders (CreateOrder, RULE-14-01/02/04). {@code customerId} —
 * gap so với contract gốc (không có field, nhưng {@code orders.customer_id} NOT NULL FK
 * users(id)): required khi {@code channel=POS_RETAIL} (Receptionist đại diện khách tại quầy tạo
 * đơn), bị bỏ qua khi {@code channel=ONLINE_APP} (BE tự lấy {@code actor.getUserId()}, không nhận
 * field client gửi — chống mạo danh khách khác).
 */
public record CreateOrderRequest(
        @NotNull UUID storeId,
        @NotNull OrderChannel channel,
        UUID customerId,
        @NotEmpty @Valid List<OrderItemLineRequest> items
) {
}
