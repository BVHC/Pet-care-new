package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 12 · docs/06-erd.md §4 purchase_request_status_enum */
public enum PurchaseRequestStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED
}
