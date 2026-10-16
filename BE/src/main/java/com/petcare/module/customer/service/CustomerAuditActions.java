package com.petcare.module.customer.service;

/** Mã action audit của module customer (convention 08 §8.3). */
final class CustomerAuditActions {

    /** Chuyển chủ thú cưng (BR-KH-08): {@code entity = pets}, before/after = {@code {customerId}}. */
    static final String PET_OWNER_TRANSFERRED = "PET_OWNER_TRANSFERRED";

    private CustomerAuditActions() {
    }
}
