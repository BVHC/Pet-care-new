package com.petcare.module.workforce.service;

/**
 * Mã action audit của module workforce (convention 08 §8.3, BR-NS-10). Bật lại / ngừng dùng ca nằm trong {@code active}
 * của before / after của {@link #SHIFT_DEFINITION_UPDATED}, không có action riêng.
 */
final class WorkforceAuditActions {

    static final String SHIFT_DEFINITION_CREATED = "SHIFT_DEFINITION_CREATED";
    static final String SHIFT_DEFINITION_UPDATED = "SHIFT_DEFINITION_UPDATED";

    /** Phân ca hộ của BRANCH_MANAGER (BR-NS-02, BR-NS-10); phân ca tự đăng ký không audit (BR-NS-11). */
    static final String SHIFT_ASSIGNMENT_CREATED = "SHIFT_ASSIGNMENT_CREATED";

    /** BRANCH_MANAGER hủy phân ca chưa bắt đầu, cả hai nguồn (BR-NS-02, BR-NS-10). */
    static final String SHIFT_ASSIGNMENT_CANCELLED = "SHIFT_ASSIGNMENT_CANCELLED";

    /** BRANCH_MANAGER ghi nhận vắng đột xuất cho phân ca (BR-NS-07, BR-NS-10); {@code before} / {@code after} là snapshot phân ca. */
    static final String SHIFT_ASSIGNMENT_ABSENCE_RECORDED = "SHIFT_ASSIGNMENT_ABSENCE_RECORDED";

    /** BRANCH_MANAGER từ chối yêu cầu nghỉ / đổi ca (BR-NS-06, BR-NS-10); {@code before} / {@code after} là snapshot yêu cầu. */
    static final String SHIFT_REQUEST_REJECTED = "SHIFT_REQUEST_REJECTED";

    /**
     * BRANCH_MANAGER duyệt yêu cầu nghỉ / đổi ca (BR-NS-06, BR-NS-10; #13): {@code before} / {@code after} là snapshot
     * yêu cầu, {@code reason} là lý do của yêu cầu. Đi cùng {@link #SHIFT_ASSIGNMENT_ON_LEAVE} ({@code LEAVE}) hoặc
     * {@link #SHIFT_ASSIGNMENT_SWAPPED} ({@code SWAP}) trong một transaction.
     */
    static final String SHIFT_REQUEST_APPROVED = "SHIFT_REQUEST_APPROVED";

    /**
     * Phân ca sang {@code ON_LEAVE} do duyệt yêu cầu nghỉ (Phân ca#3; #13): {@code before} / {@code after} là snapshot
     * phân ca (cùng loại với hủy và vắng), {@code reason} là lý do của yêu cầu.
     */
    static final String SHIFT_ASSIGNMENT_ON_LEAVE = "SHIFT_ASSIGNMENT_ON_LEAVE";

    /**
     * Phân ca đổi sang ca đích do duyệt yêu cầu đổi ca (Phân ca#5; #13 {@code SWAP}): {@code before} / {@code after} là
     * snapshot phân ca, chỉ khác {@code shiftDefinitionId}; {@code reason} là lý do của yêu cầu. Đi cùng
     * {@link #SHIFT_REQUEST_APPROVED} trong một transaction.
     */
    static final String SHIFT_ASSIGNMENT_SWAPPED = "SHIFT_ASSIGNMENT_SWAPPED";

    /** {@code audit_logs.entity_type} của yêu cầu nghỉ / đổi ca. */
    static final String SHIFT_REQUEST_ENTITY = "shift_requests";

    /** {@code audit_logs.entity_type} của ca làm việc. */
    static final String SHIFT_DEFINITION_ENTITY = "shift_definitions";

    /** {@code audit_logs.entity_type} của phân ca. */
    static final String SHIFT_ASSIGNMENT_ENTITY = "shift_assignments";

    private WorkforceAuditActions() {
    }
}
