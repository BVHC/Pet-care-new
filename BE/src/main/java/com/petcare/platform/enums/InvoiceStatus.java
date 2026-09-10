package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 6 · docs/06-erd.md §4 invoice_status_enum */
public enum InvoiceStatus {
    DRAFT,
    ISSUED,
    PAID,
    VOID,
    CANCELLED
}
