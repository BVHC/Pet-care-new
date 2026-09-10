package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 10 · docs/06-erd.md §4 package_status_enum */
public enum PackageStatus {
    PURCHASED,
    ACTIVATED,
    PARTIALLY_CONSUMED,
    FULLY_CONSUMED,
    CANCELLED,
    EXPIRED
}
