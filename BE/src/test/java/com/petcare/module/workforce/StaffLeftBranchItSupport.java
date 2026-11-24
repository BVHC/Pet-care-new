package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.petcare.platform.config.TimeConfig;

/**
 * Nền chung của các IT "nhân viên rời chi nhánh" (BR-NS-09, docs/adr/0149 bước 5): HTTP thật qua {@code POST
 * /api/staff/{id}/disable} và {@code /transfer} của identity, dữ liệu qua {@link WorkforceItSupport}. {@link #seedWorld}
 * dựng một "thế giới" cố định quanh nhân viên {@link #staff} ở chi nhánh A với đủ loại phân ca để mỗi test chỉ cần nói
 * tập nào bị hủy ({@link #cancelledByLeaving}) và tập nào phải giữ nguyên ({@link #untouched}). Mỗi lớp con có era riêng
 * (xem docs/architecture/testing-notes.md) và đồng hồ đứng ở 10:00 giờ Việt Nam.
 */
abstract class StaffLeftBranchItSupport extends ShiftAssignmentItSupport {

    /** Lý do hệ thống của phân ca và ghi chú của yêu cầu bị đóng (khớp {@code StaffLeftBranchService}). */
    protected static final String LEFT_REASON = "Nhân viên không còn làm việc tại chi nhánh";

    protected LocalDate today;
    protected long branchA;
    protected long branchB;
    protected long branchC;
    protected long superManager;
    protected long staff;
    protected long otherStaff;
    protected long morningA;
    protected long afternoonA;
    protected long morningC;

    // Phân ca của `staff` ở chi nhánh A bị hủy khi rời (chưa bắt đầu, SCHEDULED), theo thứ tự id tăng dần
    protected long futureMorning;
    protected long futureAfternoon;
    protected long todayLater;
    // Phải giữ nguyên
    protected long todayStarted;
    protected long past;
    protected long onLeave;
    protected long absent;
    protected long alreadyCancelled;
    protected long otherStaffFuture;
    protected long otherBranchFuture;

    protected long requestOnFuture;
    protected long requestOnStarted;

    /**
     * Đồng hồ đứng ở {@code now} (10:00 giờ Việt Nam): ca sáng 08:00 của hôm nay <b>đã bắt đầu</b>, ca chiều 13:00 chưa.
     * Mọi dòng chèn bằng SQL ở đúng trạng thái cần.
     */
    protected void seedWorld(Instant now) {
        begin(now);
        today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        branchA = data.branch();
        branchB = data.branch();
        branchC = data.branch();
        superManager = data.staff("SUPER_MANAGER", null);
        staff = data.staff("RECEPTIONIST", branchA);
        otherStaff = data.staff("VET", branchA);
        morningA = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoonA = definition(branchA, "Ca chiều", "13:00", "17:00", true);
        morningC = definition(branchC, "Ca sáng C", "08:00", "12:00", true);

        futureMorning = data.assignment(branchA, staff, morningA, today.plusDays(7), "SCHEDULED", "SELF", null);
        futureAfternoon = data.assignment(branchA, staff, afternoonA, today.plusDays(8), "SCHEDULED", "MANAGER", null);
        todayLater = data.assignment(branchA, staff, afternoonA, today, "SCHEDULED", "MANAGER", null);
        todayStarted = data.assignment(branchA, staff, morningA, today, "SCHEDULED", "MANAGER", null);
        past = data.assignment(branchA, staff, morningA, today.minusDays(3), "SCHEDULED", "SELF", null);
        onLeave = data.assignment(branchA, staff, morningA, today.plusDays(9), "ON_LEAVE", "SELF", superManager);
        absent = data.assignment(branchA, staff, morningA, today.plusDays(10), "ABSENT", "SELF", superManager);
        alreadyCancelled = data.assignment(branchA, staff, morningA, today.plusDays(11), "CANCELLED", "SELF",
                superManager);
        otherStaffFuture = data.assignment(branchA, otherStaff, morningA, today.plusDays(7), "SCHEDULED", "SELF", null);
        otherBranchFuture = data.assignment(branchC, staff, morningC, today.plusDays(12), "SCHEDULED", "MANAGER", null);

        requestOnFuture = pendingRequest(futureMorning, staff);
        requestOnStarted = pendingRequest(todayStarted, staff);
    }

    protected List<Long> cancelledByLeaving() {
        return List.of(futureMorning, futureAfternoon, todayLater);
    }

    /** Mọi phân ca không được đụng tới (đã bắt đầu / đã qua / đã ở trạng thái cuối / người khác / chi nhánh khác). */
    protected List<Long> untouched() {
        return List.of(todayStarted, past, onLeave, absent, alreadyCancelled, otherStaffFuture, otherBranchFuture);
    }

    protected void tearDownWorld() {
        try {
            purgeCancelOutbox(staff, otherStaff);
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- gọi identity

    protected ResponseEntity<Map<String, Object>> disable(String token, long staffId) {
        return call(HttpMethod.POST, "/api/staff/" + staffId + "/disable", token);
    }

    protected ResponseEntity<Map<String, Object>> transfer(String token, long staffId, long branchId) {
        return call(HttpMethod.POST, "/api/staff/" + staffId + "/transfer", token, "{'branchId':" + branchId + "}");
    }

    // ---------------------------------------------------------------- đọc DB

    protected String snapshotOfAssignments(List<Long> ids) {
        List<String> rows = new ArrayList<>();
        for (long id : ids) {
            rows.add(String.valueOf(jdbc.queryForMap("""
                    SELECT id, status, source, reason, status_changed_by, status_changed_at, updated_at, work_date
                    FROM shift_assignments WHERE id = ?""", id)));
        }
        return String.join("\n", rows);
    }

    protected Map<String, Object> accountRow(long accountId) {
        return jdbc.queryForMap("""
                SELECT a.status, a.is_locked, p.branch_id
                FROM accounts a JOIN staff_profiles p ON p.account_id = a.id WHERE a.id = ?""", accountId);
    }

    protected int activeSessions(long accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, accountId);
    }

    protected int staffAuditCount(long accountId, String action) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ? AND action = ?""",
                Integer.class, accountId, action);
    }

    /**
     * Dòng audit "bị từ chối quyền" mà facade {@code StaffAdminService} ghi độc lập (REQUIRES_NEW) sau rollback:
     * action {@code STAFF_ACTION_DENIED} ({@code QtAuditActions}), thực thể là tài khoản bị tác động.
     */
    protected int denialAuditCount(long actorId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action = 'STAFF_ACTION_DENIED' AND actor_account_id = ?""",
                Integer.class, actorId);
    }

    /**
     * Ảnh chụp mọi thứ một lần vô hiệu hóa / điều chuyển có thể đổi của nhân viên: tài khoản, chi nhánh, phiên còn mở,
     * audit, mọi phân ca, mọi yêu cầu và outbox hủy phân ca. Hai ảnh bằng nhau nghĩa là lệnh gọi không để lại gì.
     */
    protected String worldOf(long staffId) {
        List<Long> assignmentIds = assignmentIdsOfStaff(staffId);
        return accountRow(staffId) + "|sessions=" + activeSessions(staffId)
                + "|audit=" + staffAuditCount(staffId, "STAFF_DISABLED") + "/" + staffAuditCount(staffId, "STAFF_TRANSFERRED")
                + "|assignments=\n" + snapshotOfAssignments(assignmentIds)
                + "\n|requests=" + jdbc.queryForList("""
                        SELECT r.id || ':' || r.status || ':' || coalesce(r.decision_note, '') || ':'
                               || coalesce(r.decided_by::text, '') || ':' || coalesce(r.closed_at::text, '')
                        FROM shift_requests r JOIN shift_assignments a ON a.id = r.assignment_id
                        WHERE a.staff_id = ? ORDER BY r.id""", String.class, staffId)
                + "|outbox=" + cancelOutbox(staffId).size();
    }

    protected List<Map<String, Object>> noticePayloads(String template, long staffId) {
        return jdbc.queryForList("""
                SELECT o.payload->>'ten_ca' AS ten_ca, o.payload->>'ngay_lam' AS ngay_lam,
                       o.payload->>'gio_bat_dau' AS gio_bat_dau, o.payload->>'gio_ket_thuc' AS gio_ket_thuc,
                       o.payload->>'chi_nhanh' AS chi_nhanh, o.payload->>'ly_do' AS ly_do,
                       o.payload->>'ten_nhan_vien' AS ten_nhan_vien
                FROM notification_outbox o
                WHERE o.template_code = ?
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))
                ORDER BY o.id""", template, staffId, staffId);
    }

    protected String emailOf(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    protected String branchName(long branchId) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branchId);
    }

    // ---------------------------------------------------------------- khẳng định dùng chung

    /**
     * Sau khi {@code staff} rời chi nhánh A: đúng ba phân ca chưa bắt đầu bị hủy bởi hệ thống, yêu cầu {@code PENDING}
     * của chúng bị từ chối, mọi thứ còn lại nguyên trạng, và nhân viên nhận đúng một cặp thông báo mỗi ca.
     */
    protected void assertLeavingEffects(String untouchedBefore, String requestOnStartedBefore) {
        for (long id : cancelledByLeaving()) {
            Map<String, Object> row = assignmentRow(id);
            assertThat(row).as("phân ca %d", id).containsEntry("status", "CANCELLED")
                    .containsEntry("reason", LEFT_REASON).containsEntry("status_changed_by", null);
            assertThat(row.get("status_changed_at")).isNotNull();
        }
        Map<String, Object> closed = requestRow(requestOnFuture);
        assertThat(closed).containsEntry("status", "REJECTED").containsEntry("decision_note", LEFT_REASON)
                .containsEntry("decided_by", null);
        assertThat(closed.get("closed_at")).isNotNull();

        assertThat(snapshotOfAssignments(untouched())).as("phân ca không được đụng tới").isEqualTo(untouchedBefore);
        assertThat(String.valueOf(requestRow(requestOnStarted))).as("yêu cầu của ca đã bắt đầu giữ PENDING")
                .isEqualTo(requestOnStartedBefore);

        assertThat(cancelOutbox(staff)).as("mỗi ca bị hủy một cặp IN_APP + EMAIL").hasSize(6);
        assertThat(cancelOutbox(staff)).extracting(row -> row.get("channel"))
                .containsExactly("IN_APP", "EMAIL", "IN_APP", "EMAIL", "IN_APP", "EMAIL");
    }

    /** Payload khớp mẫu thật trong DB cho mọi dòng của nhân viên: đủ {@code required_vars}, không khóa ngoài {@code allowed_vars}, không rỗng. */
    protected void assertTemplatesFit(long staffId) {
        for (String template : List.of("SHIFT_ASSIGNMENT_CANCELLED_APP", "SHIFT_ASSIGNMENT_CANCELLED")) {
            List<String> required = jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(required_vars) FROM notification_templates WHERE code = ?",
                    String.class, template);
            List<String> allowed = jdbc.queryForList(
                    "SELECT jsonb_array_elements_text(allowed_vars) FROM notification_templates WHERE code = ?",
                    String.class, template);
            List<String> perRowKeys = jdbc.queryForList("""
                    SELECT (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(o.payload) AS k)
                    FROM notification_outbox o
                    WHERE o.template_code = ?
                      AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                    String.class, template, staffId, staffId);
            assertThat(perRowKeys).as("%s phải có dòng outbox", template).isNotEmpty();
            for (String keys : perRowKeys) {
                List<String> present = List.of(keys.split(","));
                assertThat(present).as("%s thiếu biến bắt buộc", template).containsAll(required);
                assertThat(allowed).as("%s có biến ngoài allowed_vars", template).containsAll(present);
            }
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM notification_outbox o, jsonb_each_text(o.payload) AS kv
                    WHERE o.template_code = ? AND btrim(kv.value) = ''
                      AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                    Integer.class, template, staffId, staffId)).as("%s có giá trị rỗng", template).isZero();
        }
    }
}
