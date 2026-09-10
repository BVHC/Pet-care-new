package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 5 · docs/06-erd.md §4 order_status_enum */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CONFIRMED,
    PROCESSING,
    READY,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
