package com.petcare.module.visit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dữ liệu dùng chung của IT hủy nhắc tái khám (T39 phần 6, docs/adr/0084): bệnh án của một lượt cũ có hẹn tái khám và Care
 * Task {@code FOLLOW_UP_DUE} / {@code VACCINE_DUE}, chèn thẳng bằng SQL với FK thật. Lượt cũ có mã {@code VS-FU-…} để
 * {@link #cleanUp} chỉ xóa đúng dòng của mình, không đụng lượt do IT chủ nhà tạo.
 */
public final class FollowUpItSupport {

    private FollowUpItSupport() {
    }

    /**
     * Lượt cũ của {@code petId} kèm bệnh án có hẹn tái khám {@code followUp} (có thể {@code null}).
     * {@code locked} = lượt {@code COMPLETED} và bệnh án đã khóa; ngược lại lượt {@code IN_PROGRESS}, chưa khóa.
     * {@code reminded} = ST04 đã nhắc. Trả id lượt (= khóa của bệnh án).
     */
    public static long record(JdbcTemplate jdbc, long branch, long customer, long pet, long staff, Instant at,
            LocalDate followUp, boolean locked, boolean reminded) {
        Timestamp ts = Timestamp.from(at);
        long visit = jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', ?, 'WALK_IN', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "VS-FU-" + UUID.randomUUID().toString().substring(0, 8), branch, customer, pet,
                locked ? "COMPLETED" : "IN_PROGRESS", ts, staff, staff, ts, ts, locked ? ts : null);
        jdbc.update("""
                INSERT INTO medical_records (visit_id, follow_up_date, follow_up_reminded_at, locked_at)
                VALUES (?, ?, ?, ?)""", visit, followUp, reminded ? ts : null, locked ? ts : null);
        return visit;
    }

    /**
     * Care Task {@code FOLLOW_UP_DUE} (nguồn = {@code sourceId}, id lượt) hoặc {@code VACCINE_DUE} / {@code VACCINE_OVERDUE}
     * (nguồn = id mũi tiêm) ở trạng thái {@code OPEN}, {@code DONE} hoặc {@code CANCELLED} (do {@code staff} xử lý), thỏa
     * ba CHECK trạng thái ↔ cột xử lý của V22.
     */
    public static long task(JdbcTemplate jdbc, String type, long sourceId, long branch, long customer, long pet,
            String status, long staff) {
        String column = "FOLLOW_UP_DUE".equals(type) ? "medical_record_id" : "vaccination_id";
        return jdbc.queryForObject("""
                INSERT INTO care_tasks (branch_id, customer_id, pet_id, task_type, %s, due_date, status, result, note,
                                        cancel_reason, handled_by, handled_at)
                VALUES (?, ?, ?, ?, ?, current_date, ?,
                        CASE WHEN ? = 'DONE' THEN 'REACHED' END,
                        CASE WHEN ? = 'DONE' THEN 'Đã gọi' END,
                        CASE WHEN ? = 'CANCELLED' THEN 'Lễ tân hủy' END,
                        CASE WHEN ? = 'OPEN' THEN NULL ELSE ?::bigint END,
                        CASE WHEN ? = 'OPEN' THEN NULL ELSE now() END)
                RETURNING id""".formatted(column), Long.class, branch, customer, pet, type, sourceId, status, status,
                status, status, status, staff, status);
    }

    /** Xóa Care Task của thú và các lượt {@code VS-FU-…} của {@link #record} (bệnh án trước, lượt sau). */
    public static void cleanUp(JdbcTemplate jdbc, long pet) {
        jdbc.update("DELETE FROM care_tasks WHERE pet_id = ?", pet);
        jdbc.update("""
                DELETE FROM medical_records
                WHERE visit_id IN (SELECT id FROM visits WHERE pet_id = ? AND code LIKE 'VS-FU-%')""", pet);
        jdbc.update("DELETE FROM visits WHERE pet_id = ? AND code LIKE 'VS-FU-%'", pet);
    }

    public static Instant cancelledAt(JdbcTemplate jdbc, long visit) {
        Timestamp at = jdbc.queryForObject("SELECT follow_up_cancelled_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
        return at == null ? null : at.toInstant();
    }

    public static String taskStatus(JdbcTemplate jdbc, long task) {
        return jdbc.queryForObject("SELECT status FROM care_tasks WHERE id = ?", String.class, task);
    }
}
