package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 7 · docs/06-erd.md §4 payment_status_enum */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
