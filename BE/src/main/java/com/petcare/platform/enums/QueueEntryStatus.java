package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 17 · docs/06-erd.md §4 queue_entry_status_enum */
public enum QueueEntryStatus {
    WAITING,
    CALLED,
    IN_SERVICE,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
