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

    protected static long idOf(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> contentOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }
}
