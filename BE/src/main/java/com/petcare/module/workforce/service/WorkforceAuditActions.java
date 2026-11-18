package com.petcare.module.workforce.service;

/**
 * Mã action audit của module workforce (convention 08 §8.3, BR-NS-10). Bật lại / ngừng dùng ca nằm trong {@code active}
 * của before / after của {@link #SHIFT_DEFINITION_UPDATED}, không có action riêng.
 */
final class WorkforceAuditActions {

    static final String SHIFT_DEFINITION_CREATED = "SHIFT_DEFINITION_CREATED";
    static final String SHIFT_DEFINITION_UPDATED = "SHIFT_DEFINITION_UPDATED";

    /** {@code audit_logs.entity_type} của ca làm việc. */
    static final String SHIFT_DEFINITION_ENTITY = "shift_definitions";

    private WorkforceAuditActions() {
    }
}
