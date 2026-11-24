package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Hủy phân ca (workforce-v1 #6; docs/adr/0149 bước 4) trên Postgres thật: hình dạng response, bốn hiệu ứng cùng một
 * transaction (phân ca, Yêu cầu#5, audit, outbox), lý do được strip và dùng thống nhất, payload thông báo khớp mẫu V40,
 * từng nhánh 400 / 404 / 409 (<b>trạng thái trước guard thời gian</b>, A11) và ma trận validation — mỗi nhánh lỗi không
 * để lại dấu vết nào. Era <b>năm 2053</b>; đồng hồ đứng ở 08:00 giờ Việt Nam để chạm đúng biên "đã bắt đầu".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentCancelIT.TestBeans.class})
class ShiftAssignmentCancelIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2053-03-14T01:00:00Z"); // 08:00 giờ Việt Nam
    private static final LocalDate TODAY = LocalDate.of(2053, 3, 14);
    private static final LocalDate DAY = LocalDate.of(2053, 3, 21);
    private static final String SYSTEM_NOTE = "Phân ca đã bị quản lý hủy";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long manager;
    private long staff;
    private long morning;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        purgeCancelOutbox(staff);
        finish();
    }

    // ---------------------------------------------------------------- thành công: bốn hiệu ứng, một transaction

    @Test
    void cancelReturns200WithTheContractFieldsAndRejectsThePendingRequestAndWritesOneAuditAndTwoNotices() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);

        ResponseEntity<Map<String, Object>> response = post(assignment, "  Nghỉ lễ  ");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
        assertThat(body).containsEntry("shiftAssignmentId", (int) assignment).containsEntry("status", "CANCELLED")
                .containsEntry("source", "SELF").containsEntry("reason", "Nghỉ lễ")
                .containsEntry("pendingRequestId", null).containsEntry("workDate", DAY.toString())
                .containsEntry("startTime", "08:00").containsEntry("endTime", "12:00");

        Map<String, Object> row = assignmentRow(assignment);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("reason", "Nghỉ lễ")
                .containsEntry("status_changed_by", manager);
        assertThat(row.get("status_changed_at")).isNotNull();

        Map<String, Object> closed = requestRow(request);
        assertThat(closed).containsEntry("status", "REJECTED").containsEntry("decision_note", SYSTEM_NOTE)
                .containsEntry("decided_by", null);
        assertThat(closed.get("closed_at")).isNotNull();

        assertThat(cancelAuditCount(assignment)).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data->>'status' AS before_status,
                       after_data->>'status' AS after_status, after_data->>'reason' AS after_reason
                FROM audit_logs WHERE entity_type = 'shift_assignments' AND entity_id = ?
                  AND action = 'SHIFT_ASSIGNMENT_CANCELLED'""", assignment);
        assertThat(audit).containsEntry("actor_account_id", manager).containsEntry("reason", "Nghỉ lễ")
                .containsEntry("before_status", "SCHEDULED").containsEntry("after_status", "CANCELLED")
                .containsEntry("after_reason", "Nghỉ lễ");
    }

    @Test
    void theTwoNoticesGoToTheStaffMemberNotTheManagerAndFitTheV40TemplatesExactly() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);

        assertThat(post(assignment, "  Nghỉ lễ  ").getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> notices = cancelOutbox(staff);
        assertThat(notices).hasSize(2);
        Map<String, Object> inApp = notices.get(0);
        Map<String, Object> email = notices.get(1);
        assertThat(inApp).containsEntry("channel", "IN_APP")
                .containsEntry("template_code", "SHIFT_ASSIGNMENT_CANCELLED_APP")
                .containsEntry("recipient_account_id", staff);
        assertThat(email).containsEntry("channel", "EMAIL").containsEntry("template_code", "SHIFT_ASSIGNMENT_CANCELLED")
                .containsEntry("recipient_email", emailOf(staff));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id = ?",
                Integer.class, manager)).as("người gọi không nhận gì").isZero();

        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String staffName = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?",
                String.class, staff);
        for (String template : List.of("SHIFT_ASSIGNMENT_CANCELLED_APP", "SHIFT_ASSIGNMENT_CANCELLED")) {
            assertThat(payloadField(template, staff, "ly_do")).as(template).isEqualTo("Nghỉ lễ");
            assertThat(payloadField(template, staff, "ten_ca")).startsWith("Ca sáng");
            assertThat(payloadField(template, staff, "ngay_lam")).isEqualTo("21/03/2053");
            assertThat(payloadField(template, staff, "gio_bat_dau")).isEqualTo("08:00");
            assertThat(payloadField(template, staff, "gio_ket_thuc")).isEqualTo("12:00");
            assertThat(payloadField(template, staff, "chi_nhanh")).isEqualTo(branchName);
            assertTemplateFit(template, staff);
        }
        assertThat(payloadField("SHIFT_ASSIGNMENT_CANCELLED", staff, "ten_nhan_vien")).isEqualTo(staffName);
        assertThat(payloadField("SHIFT_ASSIGNMENT_CANCELLED_APP", staff, "ten_nhan_vien")).isNull();
    }

    @Test
    void aManagerSourcedAssignmentWithoutARequestCancelsToo() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "MANAGER", null);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Đổi lịch");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("source", "MANAGER").containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE assignment_id = ?", Integer.class,
                assignment)).isZero();
        assertThat(cancelAuditCount(assignment)).isEqualTo(1);
        assertThat(cancelOutbox(staff)).hasSize(2);
    }

    @Test
    void anAssignmentAlreadySwappedToAnotherShiftReportsTheCurrentShiftInTheResponseAndTheNotice() {
        long afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        jdbc.update("UPDATE shift_assignments SET shift_definition_id = ? WHERE id = ?", afternoon, assignment);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Nghỉ lễ");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("shiftDefinitionId", (int) afternoon)
                .containsEntry("startTime", "13:00").containsEntry("endTime", "17:00");
        assertThat((String) dataOf(response).get("shiftName")).startsWith("Ca chiều");
        assertThat(payloadField("SHIFT_ASSIGNMENT_CANCELLED_APP", staff, "gio_bat_dau")).isEqualTo("13:00");
        assertThat(payloadField("SHIFT_ASSIGNMENT_CANCELLED_APP", staff, "ten_ca")).startsWith("Ca chiều");
    }

    @Test
    void aCancelledSlotCanBeAssignedAgainBecauseOnlyScheduledRowsCountForOverlapAndUniqueness() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        assertThat(post(assignment, "Nghỉ lễ").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> again = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(staff, morning, DAY.toString()));

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(2);
    }

    @Test
    void theReasonIsStoredAtTheFullColumnLengthWithVietnameseCharacters() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        String longest = "ệ".repeat(300);

        ResponseEntity<Map<String, Object>> response = post(assignment, longest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT char_length(reason) FROM shift_assignments WHERE id = ?", Integer.class,
                assignment)).isEqualTo(300);
        assertThat(payloadField("SHIFT_ASSIGNMENT_CANCELLED_APP", staff, "ly_do")).isEqualTo(longest);
    }

    // ---------------------------------------------------------------- không idempotent

    @Test
    void cancellingTwiceIs409AndTheSecondCallLeavesNoTrace() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        assertThat(post(assignment, "Nghỉ lễ").getStatusCode().value()).isEqualTo(200);
        String after = footprint(assignment, staff);

        ResponseEntity<Map<String, Object>> second = post(assignment, "Lý do khác");

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(second)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(footprint(assignment, staff)).isEqualTo(after);
        assertThat(cancelAuditCount(assignment)).isEqualTo(1);
        assertThat(cancelOutbox(staff)).hasSize(2);
    }

    // ---------------------------------------------------------------- 409 trước 400 (A11)

    @Test
    void everyFinalStatusIs409EvenWhenTheShiftIsAlreadyOverOrStartedSoStateBeatsTheTimeGuard() {
        LocalDate over = LocalDate.of(2053, 3, 1);
        for (String status : List.of("CANCELLED", "ON_LEAVE", "ABSENT")) {
            for (LocalDate day : List.of(over, TODAY)) {
                long other = definition(branch, "Ca " + status + day, "09:00", "10:00", true);
                long assignment = data.assignment(branch, staff, other, day, status, "SELF", manager);
                String before = footprint(assignment, staff);

                ResponseEntity<Map<String, Object>> response = post(assignment, "Nghỉ lễ");

                assertThat(response.getStatusCode().value()).as("%s %s", status, day).isEqualTo(409);
                assertThat(errorCode(response)).isEqualTo("INVALID_STATE_TRANSITION");
                assertThat(footprint(assignment, staff)).isEqualTo(before);
            }
        }
    }

    // ---------------------------------------------------------------- 400: ca đã bắt đầu

    @Test
    void aShiftThatStartsRightNowIsStartedAndKeepsItsPendingRequest() {
        long assignment = data.assignment(branch, staff, morning, TODAY, "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);
        String before = footprint(assignment, staff);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Nghỉ lễ");

        assertBusinessRule(response, "BR-NS-02");
        assertThat(footprint(assignment, staff)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING").containsEntry("decision_note", null);
    }

    @Test
    void aShiftThatStartsOneMinuteLaterCanStillBeCancelled() {
        long late = definition(branch, "Ca muộn", "08:01", "12:00", true);
        long assignment = data.assignment(branch, staff, late, TODAY, "SCHEDULED", "SELF", null);

        assertThat(post(assignment, "Nghỉ lễ").getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
    }

    // ---------------------------------------------------------------- 404 và đầu vào sai

    @Test
    void anUnknownAssignmentIs404AndWritesNothing() {
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_ASSIGNMENT_CANCELLED'",
                Integer.class);

        ResponseEntity<Map<String, Object>> response = post(Long.MAX_VALUE - 1, "Nghỉ lễ");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_ASSIGNMENT_CANCELLED'",
                Integer.class)).isEqualTo(audits);
    }

    @Test
    void aMissingBlankOrTooLongReasonIs400ValidationFailedAndLeavesNoTrace() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);
        String before = footprint(assignment, staff);

        for (String json : List.of("{}", "{'reason':null}", "{'reason':''}", "{'reason':'   '}",
                reasonBody("x".repeat(301)))) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, cancelPath(assignment), token, json);

            assertThat(response.getStatusCode().value()).as(json.length() > 40 ? "301 ký tự" : json).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
        }

        assertThat(footprint(assignment, staff)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING");
    }

    @Test
    void aBodyThatIsNotJsonOrIsMissingAndANonNumericIdAreMalformed400() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        String before = footprint(assignment, staff);

        ResponseEntity<Map<String, Object>> notJson = call(HttpMethod.POST, cancelPath(assignment), token,
                "khong phai json");
        ResponseEntity<Map<String, Object>> noBody = call(HttpMethod.POST, cancelPath(assignment), token);
        ResponseEntity<Map<String, Object>> badId = call(HttpMethod.POST, ASSIGNMENTS + "/abc/cancel", token,
                reasonBody("Nghỉ lễ"));

        for (ResponseEntity<Map<String, Object>> response : List.of(notJson, noBody, badId)) {
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
        }
        assertThat(footprint(assignment, staff)).isEqualTo(before);
    }

    @Test
    void extraBodyFieldsCannotChangeTheStatusTheDeciderOrTheBranch() {
        long assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        long otherBranch = data.branch();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, cancelPath(assignment), token,
                "{'reason':'Nghỉ lễ','status':'ABSENT','branchId':" + otherBranch + ",'statusChangedBy':1,"
                        + "'staffId':1}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED").containsEntry("branch_id", branch)
                .containsEntry("staff_id", staff).containsEntry("status_changed_by", manager);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> post(long assignmentId, String reason) {
        return call(HttpMethod.POST, cancelPath(assignmentId), token, reasonBody(reason));
    }

    private String emailOf(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    /** Giá trị một khóa của payload outbox (theo mẫu và nhân viên); {@code null} khi payload không có khóa đó. */
    private String payloadField(String template, long staffId, String key) {
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
    private void assertTemplateFit(String template, long staffId) {
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
        assertThat(keys).as("%s thiếu biến bắt buộc", template).containsAll(required);
        assertThat(allowed).as("%s có biến ngoài allowed_vars", template).containsAll(keys);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox o, jsonb_each_text(o.payload) AS kv
                WHERE o.template_code = ? AND btrim(kv.value) = ''
                  AND (o.recipient_account_id = ? OR o.recipient_email = (SELECT email FROM accounts WHERE id = ?))""",
                Integer.class, template, staffId, staffId)).as("%s có giá trị rỗng", template).isZero();
    }
}
