package com.petcare.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** docs/api/order-v1.md C1 — POST /orders items[] (RULE-14-01/02). */
public record OrderItemLineRequest(
        @NotNull UUID productId,
        @Positive int quantity
) {
}
