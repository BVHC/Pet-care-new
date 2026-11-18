package com.petcare.module.workforce;

import java.util.List;
import java.util.Map;

/**
 * Nền chung của các IT phân ca (workforce-v1 #4–5, docs/adr/0138 bước 3): dùng lại hạ tầng của
 * {@link ShiftDefinitionItSupport} (HTTP thật, token qua {@code SessionService}, dữ liệu qua {@link WorkforceItSupport})
 * và thêm các hàm đếm / đọc dòng phân ca. Mỗi lớp con có era riêng (xem docs/architecture/testing-notes.md).
 */
abstract class ShiftAssignmentItSupport extends ShiftDefinitionItSupport {

    protected static final String ASSIGNMENTS = "/api/shift-assignments";

    /** Body JSON thô của {@code POST /shift-assignments} (dấu nháy đơn, đổi sang nháy kép trong {@code call}). */
    protected static String body(long staffId, long definitionId, String workDate) {
        return "{'staffId':" + staffId + ",'shiftDefinitionId':" + definitionId + ",'workDate':'" + workDate + "'}";
    }

    protected int assignmentCount(long branchId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_assignments WHERE branch_id = ?", Integer.class,
                branchId);
    }

    protected int assignmentCountOfStaff(long staffId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shift_assignments WHERE staff_id = ?", Integer.class,
                staffId);
    }

    /** Số dòng audit {@code SHIFT_ASSIGNMENT_CREATED} của một nhân viên (id tài khoản duy nhất theo từng test). */
    protected int assignmentAuditCountOfStaff(long staffId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND action = 'SHIFT_ASSIGNMENT_CREATED'
                  AND after_data->>'staffId' = ?""", Integer.class, String.valueOf(staffId));
    }

    protected Map<String, Object> assignmentRow(long assignmentId) {
        return jdbc.queryForMap("""
                SELECT branch_id, staff_id, shift_definition_id, work_date::text AS work_date, status, source, reason,
                       status_changed_by, status_changed_at
                FROM shift_assignments WHERE id = ?""", assignmentId);
    }

    protected List<Long> assignmentIdsOfStaff(long staffId) {
        return jdbc.queryForList("SELECT id FROM shift_assignments WHERE staff_id = ? ORDER BY id", Long.class,
                staffId);
    }

    // ---------------------------------------------------------------- hủy phân ca (#6) và lịch của tôi (#8)

    protected static final String MINE = "/api/me/shift-assignments";
    protected static final List<String> CONTRACT_KEYS = List.of("shiftAssignmentId", "branchId", "staffId",
            "staffName", "shiftDefinitionId", "shiftName", "startTime", "endTime", "workDate", "status", "source",
            "reason", "pendingRequestId");

    protected static String cancelPath(long assignmentId) {
        return ASSIGNMENTS + "/" + assignmentId + "/cancel";
    }

    /** Body JSON thô {@code {"reason": ...}} (dấu nháy đơn, đổi sang nháy kép trong {@code call}). */
    protected static String reasonBody(String reason) {
        return "{'reason':'" + reason + "'}";
    }

    protected long pendingRequest(long assignmentId, long requesterId) {
        return data.leaveRequest(assignmentId, requesterId, "PENDING", null);
    }

    protected Map<String, Object> requestRow(long requestId) {
        return jdbc.queryForMap("""
                SELECT status, decided_by, decision_note, closed_at FROM shift_requests WHERE id = ?""", requestId);
    }

    protected int cancelAuditCount(long assignmentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND entity_id = ? AND action = 'SHIFT_ASSIGNMENT_CANCELLED'""",
                Integer.class, assignmentId);
    }

    /** Dòng outbox hủy phân ca của một nhân viên: IN_APP theo id tài khoản, EMAIL theo email (không có FK tới tài khoản). */
    protected List<Map<String, Object>> cancelOutbox(long staffId) {
        return jdbc.queryForList("""
                SELECT o.channel, o.template_code, o.recipient_account_id, o.recipient_email, o.payload::text AS payload
                FROM notification_outbox o
                WHERE o.template_code IN ('SHIFT_ASSIGNMENT_CANCELLED', 'SHIFT_ASSIGNMENT_CANCELLED_APP')
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))
                ORDER BY o.id""", staffId, staffId);
    }

    /** Xóa dòng outbox hủy phân ca của các nhân viên; gọi <b>trước</b> {@code finish()} (EMAIL không có FK nên không tự dọn). */
    protected void purgeCancelOutbox(long... staffIds) {
        for (long staffId : staffIds) {
            jdbc.update("""
                    DELETE FROM notification_outbox
                    WHERE template_code IN ('SHIFT_ASSIGNMENT_CANCELLED', 'SHIFT_ASSIGNMENT_CANCELLED_APP')
                      AND (recipient_account_id = ? OR recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                    staffId, staffId);
        }
    }

    /**
     * Ảnh chụp mọi dấu vết của một lần hủy: dòng phân ca, các yêu cầu của nó, số dòng audit và outbox. Hai ảnh bằng nhau
     * nghĩa là lệnh gọi không để lại gì (404 / 403 / 409 / 400 / rollback).
     */
    protected String footprint(long assignmentId, long staffId) {
        return assignmentRow(assignmentId) + "|requests="
                + jdbc.queryForList("""
                        SELECT status || ':' || coalesce(decision_note, '') || ':' || coalesce(closed_at::text, '')
                        FROM shift_requests WHERE assignment_id = ? ORDER BY id""", String.class, assignmentId)
                + "|audit=" + cancelAuditCount(assignmentId) + "|outbox=" + cancelOutbox(staffId).size();
    }

    protected static long idOf(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> contentOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }
}
