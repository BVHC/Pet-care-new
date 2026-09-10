package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 8 · docs/06-erd.md §4 refund_status_enum */
public enum RefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PROCESSING,
    COMPLETED,
    FAILED
}
