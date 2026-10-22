package com.petcare.module.sales.entity;

/** {@code orders.status} (docs/03 §5): OPEN → PENDING | CANCELLED, PENDING → PAID | CANCELLED; PAID, CANCELLED là cuối. */
public enum OrderStatus {
    OPEN,
    PENDING,
    PAID,
    CANCELLED
}
