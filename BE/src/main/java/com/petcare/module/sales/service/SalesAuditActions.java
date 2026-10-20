package com.petcare.module.sales.service;

/** Mã action audit của module sales (convention 08 §8.3). */
final class SalesAuditActions {

    /** Order#10: BRANCH_MANAGER hủy Order {@code PENDING} khách không thanh toán (BR-BH-05, thất thu BR-BC-02). */
    static final String ORDER_CANCELLED_UNPAID = "ORDER_CANCELLED_UNPAID";

    private SalesAuditActions() {
    }
}
