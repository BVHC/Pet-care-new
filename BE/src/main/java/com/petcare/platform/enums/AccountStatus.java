package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 1 · docs/06-erd.md §4 account_status_enum */
public enum AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    LOCKED,
    DEACTIVATED
}
