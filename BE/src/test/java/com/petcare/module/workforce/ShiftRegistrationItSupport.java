package com.petcare.module.workforce;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Nền chung của các IT đăng ký ca theo tuần (workforce-v1 #17, docs/adr/0138 mục "Đăng ký tuần — đọc #17"): đường
 * dẫn, ngày thứ Hai, khóa JSON của hợp đồng và "dấu vết" để chứng minh một lần đọc không ghi gì. Mỗi lớp con có era
 * riêng (xem docs/architecture/testing-notes.md) và gọi {@link #begin} / {@link #finish}.
 */
abstract class ShiftRegistrationItSupport extends ShiftAssignmentItSupport {

    protected static final String REGISTRATIONS = "/api/me/shift-registrations/";
    protected static final List<String> REGISTRATION_KEYS = List.of("weekStart", "requiredShifts", "shiftCount",
            "editableUntil", "assignments");
    protected static final String QUOTA_KEY = "workforce.weekly_required_shifts";

    protected static LocalDate mondayOnOrAfter(LocalDate day) {
        return day.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    protected static LocalDate firstMondayOf(int year) {
        return LocalDate.of(year, 1, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
    }

    protected static LocalDate lastMondayOf(int year) {
        return LocalDate.of(year, 12, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY));
    }

    // ---------------------------------------------------------------- ghi #15 (docs/adr/0138, N3-S7)

    /** Lý do hệ thống của Phân ca#7 và ghi chú của Yêu cầu#5 khi phân ca bị bỏ khỏi đăng ký tuần. */
    protected static final String SELF_EDIT_REASON = "Nhân viên sửa đăng ký tuần";
    protected static final String EDITED_NOTE = "Phân ca đã bị bỏ khi nhân viên sửa đăng ký tuần";

    /** {@code PUT /me/shift-registrations/{weekStart}} với body JSON thô (dấu nháy đơn đổi sang nháy kép trong {@code call}). */
    protected ResponseEntity<Map<String, Object>> put(String token, Object weekStart, String json) {
        return call(HttpMethod.PUT, REGISTRATIONS + weekStart, token, json);
    }

    protected static String item(LocalDate day, long definitionId) {
        return "{'workDate':'" + day + "','shiftDefinitionId':" + definitionId + "}";
    }

    protected static String itemsBody(String... items) {
        return "{'items':[" + String.join(",", items) + "]}";
    }

    /** Mọi phân ca (mọi trạng thái, mọi tuần) của một nhân viên theo ngày, ca, id. */
    protected List<Map<String, Object>> rowsOf(long staffId) {
        return jdbc.queryForList("""
                SELECT id, branch_id, shift_definition_id, work_date::text AS work_date, status, source, reason,
                       status_changed_by, status_changed_at
                FROM shift_assignments WHERE staff_id = ? ORDER BY work_date, shift_definition_id, id""", staffId);
    }

    protected List<Map<String, Object>> scheduledRowsOf(long staffId) {
        return rowsOf(staffId).stream().filter(row -> "SCHEDULED".equals(row.get("status"))).toList();
    }

    /** {@code xmin} từng phân ca của một nhân viên (id → xmin): bằng nhau trước / sau nghĩa là không dòng nào bị ghi. */
    protected Map<Long, String> xminsOf(long staffId) {
        Map<Long, String> out = new java.util.TreeMap<>();
        jdbc.queryForList("SELECT id, xmin::text AS x FROM shift_assignments WHERE staff_id = ?", staffId)
                .forEach(row -> out.put(((Number) row.get("id")).longValue(), (String) row.get("x")));
        return out;
    }

    /**
     * {@link #footprint(long...)} gọi bằng tên khác: {@code footprint(a, b)} với hai {@code long} bị chọn nhầm sang
     * {@code footprint(assignmentId, staffId)} của lớp cha (ném {@code EmptyResultDataAccessException}).
     */
    protected String trace(long... staffIds) {
        return footprint(staffIds);
    }

    protected long totalAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
    }

    protected long totalOutboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
    }

    protected ResponseEntity<Map<String, Object>> registration(String token, Object weekStart) {
        return call(HttpMethod.GET, REGISTRATIONS + weekStart, token);
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> assignmentsOf(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) dataOf(response).get("assignments");
    }

    protected static int intOf(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).intValue();
    }

    /**
     * Ảnh chụp mọi dấu vết một lần đọc có thể để lại quanh các nhân viên: id + {@code xmin} + trạng thái từng phân ca và
     * yêu cầu của họ, cộng số dòng audit và outbox toàn bảng (các IT chạy tuần tự, job tắt ở profile {@code test}).
     * Hai ảnh bằng nhau nghĩa là lệnh gọi không ghi gì (200, 400, 401, 403, 500).
     */
    protected String footprint(long... staffIds) {
        StringBuilder out = new StringBuilder();
        for (long staffId : staffIds) {
            out.append("staff ").append(staffId).append(" assignments=").append(jdbc.queryForObject("""
                    SELECT coalesce(string_agg(id || ':' || xmin::text || ':' || status, ',' ORDER BY id), '')
                    FROM shift_assignments WHERE staff_id = ?""", String.class, staffId));
            out.append(" requests=").append(jdbc.queryForObject("""
                    SELECT coalesce(string_agg(id || ':' || xmin::text || ':' || status, ',' ORDER BY id), '')
                    FROM shift_requests WHERE requester_id = ?""", String.class, staffId)).append('\n');
        }
        out.append("audit=").append(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class));
        out.append(" outbox=").append(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class));
        return out.toString();
    }
}
