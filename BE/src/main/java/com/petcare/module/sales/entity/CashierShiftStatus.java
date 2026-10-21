package com.petcare.module.sales.entity;

/** {@code cashier_shifts.status} (docs/03 §8): OPEN → CLOSED → RECONCILED; RECONCILED là cuối. */
public enum CashierShiftStatus {
    OPEN,
    CLOSED,
    RECONCILED
}
