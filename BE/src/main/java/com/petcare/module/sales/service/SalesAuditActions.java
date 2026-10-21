package com.petcare.module.sales.service;

/** Mã action audit của module sales (convention 08 §8.3). */
final class SalesAuditActions {

    /** Order#10: BRANCH_MANAGER hủy Order {@code PENDING} khách không thanh toán (BR-BH-05, thất thu BR-BC-02). */
    static final String ORDER_CANCELLED_UNPAID = "ORDER_CANCELLED_UNPAID";

    /** Ca thu ngân#6: BRANCH_MANAGER đối soát ca đã chốt (BR-TG-04, 05; convention 08 §8.3). */
    static final String CASHIER_SHIFT_RECONCILED = "CASHIER_SHIFT_RECONCILED";

    /** Order#8: thu tiền, mỗi Order một dòng audit (BR-QT-15; convention 08 §8.3). */
    static final String ORDER_PAID = "ORDER_PAID";

    private SalesAuditActions() {
    }
}
