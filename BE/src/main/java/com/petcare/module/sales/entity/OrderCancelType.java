package com.petcare.module.sales.entity;

/**
 * {@code orders.cancel_type} (erd §8): thất thu (BR-BC-02) chỉ tính {@code UNPAID}. {@code ONLINE_CUSTOMER} (khách hủy)
 * và {@code ONLINE_EXPIRED} (ST10 quá hạn thanh toán) dành cho Order nguồn ONLINE (V42; task 125).
 */
public enum OrderCancelType {
    VISIT_CANCELLED,
    RETAIL_UNCLOSED,
    CHECKOUT_ABORTED,
    UNPAID,
    ONLINE_CUSTOMER,
    ONLINE_EXPIRED
}
