package com.petcare.platform.enums;

/** docs/03-state-machines.md §21.2 · docs/06-erd.md §4 outbox_status_enum */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
