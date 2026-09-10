package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 13 · docs/06-erd.md §4 purchase_order_status_enum */
public enum PurchaseOrderStatus {
    ISSUED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED
}
