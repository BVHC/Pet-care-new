package com.petcare.module.visit.service;

/** Mã action audit của module visit (convention 08 §8.3). */
final class VisitAuditActions {

    /**
     * Gán lại lượt đã gọi (Visit#4, BR-TN-08, BR-QT-15; docs/adr/0063): {@code entity = visits}, before/after =
     * {@code {assigneeId, needsReassign}}, {@code reason} = lý do gán lại.
     */
    static final String VISIT_REASSIGNED = "VISIT_REASSIGNED";

    private VisitAuditActions() {
    }
}
