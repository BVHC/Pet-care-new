package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 15 · docs/06-erd.md §4 grooming_status_enum */
public enum GroomingStatus {
    WAITING,
    IN_PROGRESS,
    AWAITING_CUSTOMER_APPROVAL,
    COMPLETED,
    CANCELLED,
    ABORTED,
    REJECTED
}
