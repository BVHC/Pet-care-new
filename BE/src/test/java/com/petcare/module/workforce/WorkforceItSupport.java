package com.petcare.module.workforce;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.petcare.module.appointment.AppointmentItSupport;

/**
 * Dữ liệu IT cho workforce: chi nhánh và nhân viên lấy từ {@link AppointmentItSupport}, còn ca / phân ca / yêu cầu
 * chèn thẳng bằng SQL ở đúng trạng thái cần (thỏa các CHECK của V36). {@link #cleanUp()} xóa dòng workforce của các
 * chi nhánh đã ghi nhận <b>trước</b> rồi mới gọi {@code cleanUp()} của {@code AppointmentItSupport}, vì mọi FK là
 * {@code RESTRICT}.
 */
public class WorkforceItSupport {

    private final JdbcTemplate jdbc;
    private final AppointmentItSupport base;
    private final List<Long> branches = new ArrayList<>();

    public WorkforceItSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.base = new AppointmentItSupport(jdbc);
    }

    public long branch() {
        long id = base.branch("ACTIVE");
        branches.add(id);
        return id;
    }

    /** Nhân viên A06–A08 / A05 có hồ sơ ở chi nhánh {@code branchId}. */
    public long staff(String role, long branchId) {
        return base.staff(role, branchId);
    }

    public long definition(long branchId, String name, LocalTime start, LocalTime end, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO shift_definitions (branch_id, name, start_time, end_time, is_active)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, branchId, name + " " + UUID.randomUUID(), start, end,
                active);
    }

    /** Phân ca ở trạng thái {@code status}; cột {@code status_changed_*}, {@code reason} điền đúng CHECK. */
    public long assignment(long branchId, long staffId, long definitionId, LocalDate day, String status,
            String source, Long changedBy) {
        boolean scheduled = "SCHEDULED".equals(status);
        return jdbc.queryForObject("""
                INSERT INTO shift_assignments (branch_id, staff_id, shift_definition_id, work_date, status, source,
                                               reason, status_changed_by, status_changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, branchId, staffId, definitionId, day,
                status, source, scheduled ? null : "Lý do", scheduled ? null : changedBy,
                scheduled ? null : OffsetDateTime.now());
    }

    /** Yêu cầu {@code LEAVE} ở trạng thái {@code status}; cột quyết định điền đúng CHECK. */
    public long leaveRequest(long assignmentId, long requesterId, String status, Long decidedBy) {
        boolean pending = "PENDING".equals(status);
        return jdbc.queryForObject("""
                INSERT INTO shift_requests (assignment_id, requester_id, request_type, reason, status, decided_by,
                                            decision_note, closed_at)
                VALUES (?, ?, 'LEAVE', 'Việc gia đình', ?, ?, ?, ?) RETURNING id""", Long.class, assignmentId,
                requesterId, status, "APPROVED".equals(status) ? decidedBy : null,
                "REJECTED".equals(status) ? "Ghi chú" : null, pending ? null : OffsetDateTime.now());
    }

    public void cleanUp() {
        for (long branch : branches) {
            jdbc.update("""
                    DELETE FROM shift_requests
                    WHERE assignment_id IN (SELECT id FROM shift_assignments WHERE branch_id = ?)""", branch);
            jdbc.update("DELETE FROM shift_assignments WHERE branch_id = ?", branch);
            jdbc.update("DELETE FROM shift_definitions WHERE branch_id = ?", branch);
        }
        branches.clear();
        base.cleanUp();
    }
}
