package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Ghi nhận vắng đột xuất (workforce-v1 #7; docs/adr/0149 bước 7) trên Postgres thật: hình dạng response và giá trị từng
 * trường, bốn hiệu ứng cùng một transaction (phân ca, Yêu cầu#5, audit, outbox), lý do được strip và dùng thống nhất,
 * payload thông báo khớp mẫu V40, guard ngày BR-NS-07 (hôm nay chưa bắt đầu vẫn ghi được; biên inclusive; [CFG] đọc
 * lúc gọi; ngày theo giờ Việt Nam), 409 trước 400 (A11), ma trận validation và mỗi nhánh lỗi không để lại dấu vết. Era
 * <b>năm 2069</b>; đồng hồ đứng ở 08:00 giờ Việt Nam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentAbsenceIT.TestBeans.class})
class ShiftAssignmentAbsenceIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2069-03-14T01:00:00Z"); // 08:00 giờ Việt Nam
    private static final LocalDate TODAY = LocalDate.of(2069, 3, 14);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    /** Giá trị gốc của [CFG] {@code workforce.absence_report_max_days} (V40). */
    private static final String CONFIG_KEY = "workforce.absence_report_max_days";
    private static final String CONFIG_DEFAULT = "7";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private SystemConfigService configService;

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
        try {
            restoreConfig();
            purgeAbsenceOutbox(staff);
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- thành công: giá trị từng trường

    @Test
    void absentReturns200WithTheContractFieldsAndTheRightValuesAndRejectsThePendingRequest() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);
        String staffName = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?",
                String.class, staff);

        ResponseEntity<Map<String, Object>> response = post(assignment, "  Nhân viên báo ốm  ");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("message", "Đã ghi nhận vắng đột xuất").containsEntry("code", 200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
        assertThat(body).containsEntry("shiftAssignmentId", (int) assignment).containsEntry("branchId", (int) branch)
                .containsEntry("staffId", (int) staff).containsEntry("staffName", staffName)
                .containsEntry("shiftDefinitionId", (int) morning).containsEntry("status", "ABSENT")
                .containsEntry("source", "SELF").containsEntry("reason", "Nhân viên báo ốm")
                .containsEntry("pendingRequestId", null).containsEntry("workDate", YESTERDAY.toString())
                .containsEntry("startTime", "08:00").containsEntry("endTime", "12:00");
        assertThat((String) body.get("shiftName")).startsWith("Ca sáng");

        Map<String, Object> row = assignmentRow(assignment);
        assertThat(row).containsEntry("status", "ABSENT").containsEntry("reason", "Nhân viên báo ốm")
                .containsEntry("status_changed_by", manager).containsEntry("source", "SELF");
        assertThat(row.get("status_changed_at")).isNotNull();

        Map<String, Object> closed = requestRow(request);
        assertThat(closed).containsEntry("status", "REJECTED").containsEntry("decision_note", ABSENCE_NOTE)
                .containsEntry("decided_by", null);
        assertThat(closed.get("closed_at")).isNotNull();

        assertThat(absenceAuditCount(assignment)).isEqualTo(1);
        assertThat(absenceOutbox(staff)).hasSize(2);
    }

    @Test
    void theStrippedReasonIsTheSameInTheResponseTheRowTheAuditAndTheNotices() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);

        ResponseEntity<Map<String, Object>> response = post(assignment, "  Nhân viên báo ốm  ");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("reason", "Nhân viên báo ốm");
        assertThat(assignmentRow(assignment)).containsEntry("reason", "Nhân viên báo ốm");
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data->>'status' AS before_status,
                       after_data->>'status' AS after_status, after_data->>'reason' AS after_reason,
                       before_data->>'reason' AS before_reason
                FROM audit_logs WHERE entity_type = 'shift_assignments' AND entity_id = ?
                  AND action = 'SHIFT_ASSIGNMENT_ABSENCE_RECORDED'""", assignment);
        assertThat(audit).containsEntry("actor_account_id", manager).containsEntry("reason", "Nhân viên báo ốm")
                .containsEntry("before_status", "SCHEDULED").containsEntry("after_status", "ABSENT")
                .containsEntry("after_reason", "Nhân viên báo ốm").containsEntry("before_reason", null);
        for (String template : List.of("SHIFT_ABSENCE_RECORDED_APP", "SHIFT_ABSENCE_RECORDED")) {
            assertThat(outboxPayloadField(template, staff, "ly_do")).as(template).isEqualTo("Nhân viên báo ốm");
        }
    }

    @Test
    void theTwoNoticesGoToTheStaffMemberNotTheManagerAndFitTheV40AbsenceTemplatesExactly() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);

        assertThat(post(assignment, "Nhân viên báo ốm").getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> notices = absenceOutbox(staff);
        assertThat(notices).hasSize(2);
        assertThat(notices.get(0)).containsEntry("channel", "IN_APP")
                .containsEntry("template_code", "SHIFT_ABSENCE_RECORDED_APP")
                .containsEntry("recipient_account_id", staff);
        assertThat(notices.get(1)).containsEntry("channel", "EMAIL").containsEntry("template_code", "SHIFT_ABSENCE_RECORDED")
                .containsEntry("recipient_email", accountEmail(staff));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id = ?",
                Integer.class, manager)).as("người gọi không nhận gì").isZero();

        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String staffName = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?",
                String.class, staff);
        for (String template : List.of("SHIFT_ABSENCE_RECORDED_APP", "SHIFT_ABSENCE_RECORDED")) {
            assertThat(outboxPayloadField(template, staff, "ten_ca")).as(template).startsWith("Ca sáng");
            assertThat(outboxPayloadField(template, staff, "ngay_lam")).isEqualTo("13/03/2069");
            assertThat(outboxPayloadField(template, staff, "chi_nhanh")).isEqualTo(branchName);
            assertThat(outboxPayloadField(template, staff, "gio_bat_dau")).as("mẫu vắng không có giờ ca").isNull();
            assertThat(outboxPayloadField(template, staff, "gio_ket_thuc")).isNull();
            assertOutboxFitsTemplate(template, staff);
        }
        assertThat(outboxPayloadField("SHIFT_ABSENCE_RECORDED", staff, "ten_nhan_vien")).isEqualTo(staffName);
        assertThat(outboxPayloadField("SHIFT_ABSENCE_RECORDED_APP", staff, "ten_nhan_vien")).isNull();
    }

    @Test
    void aManagerSourcedAssignmentWithoutARequestIsRecordedAbsentToo() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "MANAGER", null);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Không đến");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("source", "MANAGER").containsEntry("status", "ABSENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_requests WHERE assignment_id = ?", Integer.class,
                assignment)).isZero();
        assertThat(absenceAuditCount(assignment)).isEqualTo(1);
        assertThat(absenceOutbox(staff)).hasSize(2);
    }

    @Test
    void anAssignmentAlreadySwappedToAnotherShiftReportsTheCurrentShiftInTheResponseAndTheNotice() {
        long afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        jdbc.update("UPDATE shift_assignments SET shift_definition_id = ? WHERE id = ?", afternoon, assignment);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Nhân viên báo ốm");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("shiftDefinitionId", (int) afternoon)
                .containsEntry("startTime", "13:00").containsEntry("endTime", "17:00");
        assertThat(outboxPayloadField("SHIFT_ABSENCE_RECORDED_APP", staff, "ten_ca")).startsWith("Ca chiều");
    }

    @Test
    void theReasonIsStoredAtTheFullColumnLengthWithVietnameseCharacters() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        String longest = "ệ".repeat(300);

        ResponseEntity<Map<String, Object>> response = post(assignment, longest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT char_length(reason) FROM shift_assignments WHERE id = ?", Integer.class,
                assignment)).isEqualTo(300);
        assertThat(outboxPayloadField("SHIFT_ABSENCE_RECORDED_APP", staff, "ly_do")).isEqualTo(longest);
    }

    @Test
    void aStaffMemberWhoHasSinceMovedToAnotherBranchIsStillRecordedAndTheNoticeNamesTheAssignmentsBranch() {
        long otherBranch = data.branch();
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, staff);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Nhân viên báo ốm");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("branchId", (int) branch);
        assertThat(outboxPayloadField("SHIFT_ABSENCE_RECORDED_APP", staff, "chi_nhanh"))
                .isEqualTo(jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch));
    }

    @Test
    void aPendingRequestLeftOnAShiftThatIsAlreadyOverIsClosedByTheAbsence() {
        // Duyệt phải trước giờ bắt đầu (BR-NS-06) và không có job dọn, nên yêu cầu chờ có thể treo trên ca đã qua.
        long assignment = data.assignment(branch, staff, morning, TODAY.minusDays(5), "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);

        assertThat(post(assignment, "Báo muộn").getStatusCode().value()).isEqualTo(200);

        assertThat(requestRow(request)).containsEntry("status", "REJECTED").containsEntry("decision_note", ABSENCE_NOTE);
    }

    // ---------------------------------------------------------------- guard ngày BR-NS-07

    @Test
    void aShiftThatStartsLaterTodayAndOneThatAlreadyStartedTodayAreBothRecordedBecauseOnlyTheDateIsChecked() {
        long later = definition(branch, "Ca chiều", "14:00", "18:00", true);
        long started = definition(branch, "Ca sớm", "07:00", "11:00", true);
        long first = data.assignment(branch, staff, later, TODAY, "SCHEDULED", "SELF", null);
        long second = data.assignment(branch, staff, started, TODAY, "SCHEDULED", "MANAGER", null);

        assertThat(post(first, "Báo ốm từ sáng").getStatusCode().value()).isEqualTo(200);
        assertThat(post(second, "Không đến").getStatusCode().value()).isEqualTo(200);

        assertThat(assignmentRow(first)).containsEntry("status", "ABSENT");
        assertThat(assignmentRow(second)).containsEntry("status", "ABSENT");
    }

    @Test
    void theLimitIsInclusiveSoExactlySevenDaysBackIsRecordedAndEightIsNot() {
        long seven = data.assignment(branch, staff, morning, TODAY.minusDays(7), "SCHEDULED", "SELF", null);
        long eight = data.assignment(branch, staff, definition(branch, "Ca khác", "13:00", "17:00", true),
                TODAY.minusDays(8), "SCHEDULED", "SELF", null);
        long eightRequest = pendingRequest(eight, staff);

        assertThat(post(seven, "Báo muộn").getStatusCode().value()).isEqualTo(200);
        // Chụp sau lần ghi thành công: nó đã thêm hai dòng outbox của cùng nhân viên, không thuộc lần gọi quá hạn.
        String beforeEight = absenceFootprint(eight, staff);
        ResponseEntity<Map<String, Object>> tooOld = post(eight, "Báo quá muộn");

        assertBusinessRule(tooOld, "BR-NS-07");
        assertThat(absenceFootprint(eight, staff)).isEqualTo(beforeEight);
        assertThat(requestRow(eightRequest)).containsEntry("status", "PENDING");
    }

    @Test
    void aShiftOfTomorrowIs400AndLeavesItsPendingRequestUntouched() {
        long assignment = data.assignment(branch, staff, morning, TODAY.plusDays(1), "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);
        String before = absenceFootprint(assignment, staff);

        ResponseEntity<Map<String, Object>> response = post(assignment, "Báo trước");

        assertBusinessRule(response, "BR-NS-07");
        assertThat(message(response)).as("mã rule chỉ xuất hiện một lần").containsOnlyOnce("BR-NS-07");
        assertThat(absenceFootprint(assignment, staff)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING");
    }

    @Test
    void theLimitIsReadFromTheConfigOnEveryCallSoANewValueAppliesFromTheNextCheck() {
        long three = data.assignment(branch, staff, morning, TODAY.minusDays(3), "SCHEDULED", "SELF", null);
        long four = data.assignment(branch, staff, definition(branch, "Ca khác", "13:00", "17:00", true),
                TODAY.minusDays(4), "SCHEDULED", "SELF", null);
        long ten = data.assignment(branch, staff, definition(branch, "Ca thứ ba", "18:00", "20:00", true),
                TODAY.minusDays(10), "SCHEDULED", "SELF", null);

        setConfig("3");
        assertThat(post(three, "Báo ốm").getStatusCode().value()).isEqualTo(200);
        assertBusinessRule(post(four, "Báo ốm"), "BR-NS-07");
        assertThat(message(post(four, "Báo ốm"))).contains("3 ngày");

        setConfig("30");
        assertThat(post(ten, "Báo ốm").getStatusCode().value()).isEqualTo(200);
        assertThat(post(four, "Báo ốm").getStatusCode().value()).as("giá trị mới áp từ lần kiểm kế tiếp").isEqualTo(200);
    }

    @Test
    void todayIsTheVietnameseCalendarDayEvenWhenTheUtcDateIsStillTheDayBefore() {
        // 2069-03-13T17:30Z là 00:30 ngày 14/03 giờ Việt Nam.
        clock.set(Instant.parse("2069-03-13T17:30:00Z"));
        String lateToken = token(manager);
        long today = data.assignment(branch, staff, morning, TODAY, "SCHEDULED", "SELF", null);
        long tomorrow = data.assignment(branch, staff, definition(branch, "Ca khác", "13:00", "17:00", true),
                TODAY.plusDays(1), "SCHEDULED", "SELF", null);

        ResponseEntity<Map<String, Object>> todayResponse = call(HttpMethod.POST, absentPath(today), lateToken,
                reasonBody("Nhân viên báo ốm"));
        ResponseEntity<Map<String, Object>> tomorrowResponse = call(HttpMethod.POST, absentPath(tomorrow), lateToken,
                reasonBody("Nhân viên báo ốm"));

        assertThat(todayResponse.getStatusCode().value()).as("14/03 là hôm nay theo giờ Việt Nam").isEqualTo(200);
        assertBusinessRule(tomorrowResponse, "BR-NS-07");
    }

    // ---------------------------------------------------------------- không idempotent, 409 trước 400 (A11)

    @Test
    void recordingTwiceIs409AndTheSecondCallLeavesNoTrace() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        assertThat(post(assignment, "Nhân viên báo ốm").getStatusCode().value()).isEqualTo(200);
        String after = absenceFootprint(assignment, staff);

        ResponseEntity<Map<String, Object>> second = post(assignment, "Lý do khác");

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(second)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(absenceFootprint(assignment, staff)).isEqualTo(after);
        assertThat(absenceAuditCount(assignment)).isEqualTo(1);
        assertThat(absenceOutbox(staff)).hasSize(2);
        assertThat(assignmentRow(assignment)).containsEntry("reason", "Nhân viên báo ốm");
    }

    @Test
    void everyFinalStatusIs409EvenWhenTheDateIsAlsoWrongSoStateBeatsTheDateGuard() {
        for (String status : List.of("CANCELLED", "ON_LEAVE", "ABSENT")) {
            for (LocalDate day : List.of(TODAY.minusDays(30), TODAY.plusDays(5), TODAY)) {
                long other = definition(branch, "Ca " + status + day, "09:00", "10:00", true);
                long assignment = data.assignment(branch, staff, other, day, status, "SELF", manager);
                String before = absenceFootprint(assignment, staff);

                ResponseEntity<Map<String, Object>> response = post(assignment, "Nhân viên báo ốm");

                assertThat(response.getStatusCode().value()).as("%s %s", status, day).isEqualTo(409);
                assertThat(errorCode(response)).isEqualTo("INVALID_STATE_TRANSITION");
                assertThat(absenceFootprint(assignment, staff)).isEqualTo(before);
            }
        }
    }

    // ---------------------------------------------------------------- 404 và đầu vào sai

    @Test
    void anUnknownAssignmentIs404AndWritesNothing() {
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_ASSIGNMENT_ABSENCE_RECORDED'",
                Integer.class);

        ResponseEntity<Map<String, Object>> response = post(Long.MAX_VALUE - 1, "Nhân viên báo ốm");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_ASSIGNMENT_ABSENCE_RECORDED'",
                Integer.class)).isEqualTo(audits);
    }

    @Test
    void aMissingBlankOrTooLongReasonIs400ValidationFailedAndLeavesNoTrace() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        long request = pendingRequest(assignment, staff);
        String before = absenceFootprint(assignment, staff);

        // U+3000 (khoảng trắng ideographic) và U+2003 (em space): @NotBlank của Hibernate Validator để lọt, strip() thì
        // làm rỗng; nếu DTO không chặn thì entity ném IllegalArgumentException và request trả 500.
        for (String json : List.of("{}", "{'reason':null}", "{'reason':''}", "{'reason':'   '}",
                reasonBody("　"), reasonBody("  　"), reasonBody("x".repeat(301)))) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, absentPath(assignment), token, json);

            assertThat(response.getStatusCode().value()).as(json.length() > 40 ? "301 ký tự" : json).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
            assertThat(message(response)).contains("reason");
        }

        assertThat(absenceFootprint(assignment, staff)).isEqualTo(before);
        assertThat(requestRow(request)).containsEntry("status", "PENDING");
    }

    @Test
    void aBodyThatIsNotJsonOrIsMissingAndANonNumericIdAreMalformed400() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        String before = absenceFootprint(assignment, staff);

        ResponseEntity<Map<String, Object>> notJson = call(HttpMethod.POST, absentPath(assignment), token,
                "khong phai json");
        ResponseEntity<Map<String, Object>> noBody = call(HttpMethod.POST, absentPath(assignment), token);
        ResponseEntity<Map<String, Object>> badId = call(HttpMethod.POST, ASSIGNMENTS + "/abc/absent", token,
                reasonBody("Nhân viên báo ốm"));

        for (ResponseEntity<Map<String, Object>> response : List.of(notJson, noBody, badId)) {
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
        }
        assertThat(absenceFootprint(assignment, staff)).isEqualTo(before);
    }

    @Test
    void extraBodyFieldsCannotChangeTheStatusTheDeciderOrTheBranch() {
        long assignment = data.assignment(branch, staff, morning, YESTERDAY, "SCHEDULED", "SELF", null);
        long otherBranch = data.branch();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, absentPath(assignment), token,
                "{'reason':'Nhân viên báo ốm','status':'CANCELLED','branchId':" + otherBranch
                        + ",'statusChangedBy':1,'staffId':1}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).containsEntry("status", "ABSENT").containsEntry("branch_id", branch)
                .containsEntry("staff_id", staff).containsEntry("status_changed_by", manager);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> post(long assignmentId, String reason) {
        return call(HttpMethod.POST, absentPath(assignmentId), token, reasonBody(reason));
    }

    private void setConfig(String value) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", value, CONFIG_KEY);
        configService.reload();
    }

    /** Khôi phục giá trị V40 và nạp lại bộ nhớ: bảng cấu hình dùng chung giữa các IT. */
    private void restoreConfig() {
        setConfig(CONFIG_DEFAULT);
    }
}
