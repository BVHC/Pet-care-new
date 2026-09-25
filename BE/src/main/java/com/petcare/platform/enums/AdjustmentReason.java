package com.petcare.platform.enums;

/** docs/02-business-rules.md RULE-12-02 · docs/06-erd.md §3.5 inventory_adjustments.reason */
public enum AdjustmentReason {
    DAMAGE,
    EXPIRY,
    THEFT,
    COUNT_VARIANCE,
    TRANSIT_VARIANCE
}
