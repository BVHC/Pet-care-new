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
