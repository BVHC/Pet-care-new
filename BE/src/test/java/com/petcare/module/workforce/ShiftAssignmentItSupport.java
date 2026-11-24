package com.petcare.module.workforce;

import java.util.List;
import java.util.Map;

/**
 * Nền chung của các IT phân ca (workforce-v1 #4–5, docs/adr/0149 bước 3): dùng lại hạ tầng của
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

    // ---------------------------------------------------------------- ghi vắng đột xuất (#7, docs/adr/0149 bước 7)

    protected static final String ABSENCE_ACTION = "SHIFT_ASSIGNMENT_ABSENCE_RECORDED";
    /** Ghi chú hệ thống của Yêu cầu#5 khi phân ca được ghi nhận vắng (khác ghi chú của hủy phân ca). */
    protected static final String ABSENCE_NOTE = "Phân ca đã được ghi nhận vắng đột xuất";

    protected static String absentPath(long assignmentId) {
        return ASSIGNMENTS + "/" + assignmentId + "/absent";
    }

    protected int absenceAuditCount(long assignmentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND entity_id = ? AND action = 'SHIFT_ASSIGNMENT_ABSENCE_RECORDED'""",
                Integer.class, assignmentId);
    }

    /** Dòng outbox vắng của một nhân viên: IN_APP theo id tài khoản, EMAIL theo email (không có FK tới tài khoản). */
    protected List<Map<String, Object>> absenceOutbox(long staffId) {
        return jdbc.queryForList("""
                SELECT o.channel, o.template_code, o.recipient_account_id, o.recipient_email, o.payload::text AS payload
                FROM notification_outbox o
                WHERE o.template_code IN ('SHIFT_ABSENCE_RECORDED', 'SHIFT_ABSENCE_RECORDED_APP')
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))
                ORDER BY o.id""", staffId, staffId);
    }

    /** Xóa dòng outbox vắng của các nhân viên; gọi <b>trước</b> {@code finish()} (EMAIL không có FK nên không tự dọn). */
    protected void purgeAbsenceOutbox(long... staffIds) {
        for (long staffId : staffIds) {
            jdbc.update("""
                    DELETE FROM notification_outbox
                    WHERE template_code IN ('SHIFT_ABSENCE_RECORDED', 'SHIFT_ABSENCE_RECORDED_APP')
                      AND (recipient_account_id = ? OR recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                    staffId, staffId);
        }
    }

    /** Như {@link #footprint} nhưng đếm audit và outbox của <b>ghi vắng</b>: hai ảnh bằng nhau = lệnh gọi không để lại gì. */
    protected String absenceFootprint(long assignmentId, long staffId) {
        return assignmentRow(assignmentId) + "|requests="
                + jdbc.queryForList("""
                        SELECT status || ':' || coalesce(decision_note, '') || ':' || coalesce(closed_at::text, '')
                        FROM shift_requests WHERE assignment_id = ? ORDER BY id""", String.class, assignmentId)
                + "|audit=" + absenceAuditCount(assignmentId) + "|outbox=" + absenceOutbox(staffId).size();
    }

    protected String accountEmail(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    /** Giá trị một khóa của payload outbox (theo mẫu và nhân viên); {@code null} khi payload không có khóa đó. */
    protected String outboxPayloadField(String template, long staffId, String key) {
        return jdbc.queryForObject("""
                SELECT o.payload->>? FROM notification_outbox o
                WHERE o.template_code = ?
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                String.class, key, template, staffId, staffId);
    }

    /**
     * Payload khớp mẫu thật trong DB: đủ {@code required_vars}, không khóa ngoài {@code allowed_vars}, không giá trị
     * rỗng — nếu không, worker ST20 coi là lỗi và thử lại mãi.
     */
    protected void assertOutboxFitsTemplate(String template, long staffId) {
        List<String> keys = jdbc.queryForList("""
                SELECT jsonb_object_keys(o.payload) FROM notification_outbox o
                WHERE o.template_code = ?
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                String.class, template, staffId, staffId);
        List<String> required = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(required_vars) FROM notification_templates WHERE code = ?",
                String.class, template);
        List<String> allowed = jdbc.queryForList(
                "SELECT jsonb_array_elements_text(allowed_vars) FROM notification_templates WHERE code = ?",
                String.class, template);
        org.assertj.core.api.Assertions.assertThat(keys).as("%s thiếu biến bắt buộc", template).containsAll(required);
        org.assertj.core.api.Assertions.assertThat(allowed).as("%s có biến ngoài allowed_vars", template)
                .containsAll(keys);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox o, jsonb_each_text(o.payload) AS kv
                WHERE o.template_code = ? AND btrim(kv.value) = ''
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                Integer.class, template, staffId, staffId)).as("%s có giá trị rỗng", template).isZero();
    }

    protected static long idOf(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> contentOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }
}
