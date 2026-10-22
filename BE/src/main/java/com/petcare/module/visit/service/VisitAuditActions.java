package com.petcare.module.visit.service;

/** Mã action audit của module visit (convention 08 §8.3). */
final class VisitAuditActions {

    /**
     * Gán lại lượt đã gọi (Visit#4, BR-TN-08, BR-QT-15; docs/adr/0063): {@code entity = visits}, before/after =
     * {@code {assigneeId, needsReassign}}, {@code reason} = lý do gán lại.
     */
    static final String VISIT_REASSIGNED = "VISIT_REASSIGNED";

    /**
     * Ghi bệnh án khi lượt còn {@code IN_PROGRESS} (visit-v1 #13, BR-KB-01, BR-TN-08, BR-QT-15; docs/adr/0064 §2):
     * {@code entity = medical_records}, id = {@code visitId}, before/after = {@code {examination, diagnosis,
     * treatmentPlan, internalNote, followUpDate}} (before rỗng ở lần ghi đầu). Là nguồn "ai ghi phần nào" khi gán lại lượt.
     */
    static final String MEDICAL_RECORD_UPDATED = "MEDICAL_RECORD_UPDATED";

    /**
     * Thêm bản bổ sung sau khi bệnh án khóa (visit-v1 #15, BR-KB-01, BR-QT-15; docs/adr/0064 §2):
     * {@code entity = medical_record_addenda}, id = id bản bổ sung, after = {@code {content, isInternal}}.
     */
    static final String MEDICAL_RECORD_ADDENDUM_ADDED = "MEDICAL_RECORD_ADDENDUM_ADDED";

    private VisitAuditActions() {
    }
}
