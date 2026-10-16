package com.petcare.module.sales.entity;

/** {@code orders.cancel_type} (erd §8): thất thu (BR-BC-02) chỉ tính {@code UNPAID}. */
public enum OrderCancelType {
    VISIT_CANCELLED,
    RETAIL_UNCLOSED,
    CHECKOUT_ABORTED,
    UNPAID
}
