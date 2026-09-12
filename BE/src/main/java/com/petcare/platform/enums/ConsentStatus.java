package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 16 · docs/06-erd.md §4 consent_status_enum */
public enum ConsentStatus {
    REQUESTED,
    ACTIVE,
    REVOKED,
    EXPIRED
}
