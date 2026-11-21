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
 * Từ chối yêu cầu nghỉ / đổi ca (workforce-v1 #14; docs/adr/0139) trên Postgres thật: chỉ yêu cầu → {@code REJECTED}
 * (Yêu cầu#4) với {@code decided_by} là quản lý, lý do đã {@code strip()} và {@code closed_at} từ đồng hồ; phân ca giữ
 * nguyên (kể cả {@code xmin}); một dòng audit; thông báo IN_APP rồi EMAIL khớp mẫu V40; POST = GET; mọi trạng thái đã
 * đóng là 409; từ chối được cả yêu cầu treo trên ca đã qua; gửi lại sau từ chối được; mọi đầu vào sai ra đúng
 * {@code errorCode} và không để lại dấu vết. Era <b>năm 2076</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestRejectIT.TestBeans.class})
class ShiftRequestRejectIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2076-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2076, 3, 21);
    private static final List<String> ENVELOPE = List.of("success", "errorCode", "message", "statusCode", "timestamp",
            "traceId");

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
    private long afternoon;
    private long assignment;
    private String managerToken;
    private String staffToken;
    private final List<ResponseEntity<Map<String, Object>>> seen = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        managerToken = token(manager);
        staffToken = token(staff);
    }

    @AfterEach
    void tearDown() {
        try {
            assertThat(seen).as("không có lỗi đầu vào nào được phép ra 5xx")
                    .allSatisfy(response -> assertThat(response.getStatusCode().is5xxServerError()).isFalse());
            assertRejectedRequestsHaveExactlyOneAudit();
        } finally {
            purgeRejectedOutbox(staff);
            purgeCancelOutbox(staff);
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void rejectReturns200WithTheFifteenKeysAndClosesOnlyTheRequest() {
        long request = pendingRequest(assignment, staff);
        Map<String, Object> assignmentBefore = assignmentRow(assignment);
        String xminBefore = assignmentXmin(assignment);

        ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(message(response)).isEqualTo("Đã từ chối yêu cầu");
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(body).containsEntry("shiftRequestId", (int) request).containsEntry("status", "REJECTED")
                .containsEntry("decidedBy", (int) manager).containsEntry("decisionNote", NOTE)
                .containsEntry("requesterId", (int) staff).containsEntry("type", "LEAVE")
                .containsEntry("reason", "Việc gia đình").containsEntry("targetShiftDefinitionId", null)
                .containsEntry("targetShiftName", null);
        assertThat(Instant.parse((String) body.get("closedAt"))).isEqualTo(NOW);

        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE).containsEntry("reason", "Việc gia đình");
        assertThat(fullRequestRow(request).get("closed_at")).isNotNull();
        assertThat(assignmentRow(assignment)).as("phân ca giữ nguyên").isEqualTo(assignmentBefore)
                .containsEntry("status", "SCHEDULED").containsEntry("shift_definition_id", morning);
        assertThat(assignmentXmin(assignment)).as("không có UPDATE nào chạm phân ca").isEqualTo(xminBefore);
    }

    @Test
    void rejectWritesExactlyOneAuditWithBeforePendingAfterRejectedTheNoteAsReasonAndTheManagerAsActor() {
        long request = pendingRequest(assignment, staff);

        assertThat(reject(request, NOTE).getStatusCode().value()).isEqualTo(200);

        assertThat(rejectAuditCount(request)).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, entity_type, reason, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'shift_requests' AND entity_id = ?""", request);
        assertThat(audit).containsEntry("actor_account_id", manager).containsEntry("reason", NOTE);
        assertThat((String) audit.get("before_data")).contains("\"status\": \"PENDING\"")
                .contains("\"requesterId\": " + staff).contains("\"type\": \"LEAVE\"");
        assertThat((String) audit.get("after_data")).contains("\"status\": \"REJECTED\"")
                .contains("\"decidedBy\": " + manager).contains("\"decisionNote\": \"" + NOTE + "\"");
    }

    @Test
    void rejectNotifiesTheRequesterInAppThenByEmailAndThePayloadFitsTheRealTemplates() {
        long request = pendingRequest(assignment, staff);

        assertThat(reject(request, NOTE).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> outbox = rejectedOutbox(staff);
        assertThat(outbox).hasSize(2);
        assertThat(outbox.get(0)).containsEntry("channel", "IN_APP").containsEntry("template_code", REJECTED_APP)
                .containsEntry("recipient_account_id", staff).containsEntry("recipient_email", null);
        assertThat(outbox.get(1)).containsEntry("channel", "EMAIL").containsEntry("template_code", REJECTED_MAIL)
                .containsEntry("recipient_email", accountEmail(staff)).containsEntry("recipient_account_id", null);
        assertOutboxFitsTemplate(REJECTED_APP, staff);
        assertOutboxFitsTemplate(REJECTED_MAIL, staff);

        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String staffName = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?",
                String.class, staff);
        for (String template : List.of(REJECTED_APP, REJECTED_MAIL)) {
            assertThat(outboxPayloadField(template, staff, "mo_ta_yeu_cau")).startsWith("nghỉ ca Ca sáng ")
                    .endsWith(" ngày 21/03/2076");
            assertThat(outboxPayloadField(template, staff, "chi_nhanh")).isEqualTo(branchName);
            assertThat(outboxPayloadField(template, staff, "ly_do")).isEqualTo(NOTE);
        }
        assertThat(outboxPayloadField(REJECTED_MAIL, staff, "ten_nhan_vien")).isEqualTo(staffName);
        assertThat(outboxPayloadField(REJECTED_APP, staff, "ten_nhan_vien")).as("ngoài required_vars của bản trong app")
                .isNull();
    }

    @Test
    void aSwapRequestIsRejectedKeepingItsTargetTheAssignmentOnTheOriginalShiftAndTheNoticeNamesBothShifts() {
        long request = data.swapRequest(assignment, staff, afternoon, "PENDING", null);

        ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("type", "SWAP").containsEntry("status", "REJECTED")
                .containsEntry("targetShiftDefinitionId", (int) afternoon);
        assertThat((String) dataOf(response).get("targetShiftName")).startsWith("Ca chiều");
        assertThat(assignmentRow(assignment)).containsEntry("shift_definition_id", morning)
                .containsEntry("status", "SCHEDULED");
        assertThat(outboxPayloadField(REJECTED_APP, staff, "mo_ta_yeu_cau")).startsWith("đổi ca Ca sáng ")
                .contains(" sang ca Ca chiều ").endsWith(" ngày 21/03/2076");
    }

    @Test
    void theAnswerOfThePostIsExactlyTheRowTheBranchListShowsAfterwards() {
        long request = pendingRequest(assignment, staff);

        Map<String, Object> posted = dataOf(reject(request, NOTE));

        ResponseEntity<Map<String, Object>> listed = call(HttpMethod.GET, LIST_BRANCH + "?status=REJECTED",
                managerToken);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = contentOf(dataOf(listed)).stream()
                .filter(item -> idOf(item, "shiftRequestId") == request).findFirst().orElseThrow();
        assertThat(row).as("POST = GET, kể cả closedAt (cắt micro giây)").isEqualTo(posted);
    }

    @Test
    void afterwardsTheOtherEndpointsShowTheRejectionAndNoPendingRequestRemains() {
        long request = pendingRequest(assignment, staff);
        assertThat(ids(call(HttpMethod.GET, LIST_BRANCH, managerToken))).containsExactly(request);

        assertThat(reject(request, NOTE).getStatusCode().value()).isEqualTo(200);

        assertThat(ids(call(HttpMethod.GET, LIST_BRANCH, managerToken))).as("hàng chờ mặc định = PENDING").isEmpty();
        ResponseEntity<Map<String, Object>> mine = call(HttpMethod.GET, REQUESTS + "?status=REJECTED", staffToken);
        assertThat(contentOf(dataOf(mine))).singleElement().satisfies(item -> {
            assertThat(idOf(item, "shiftRequestId")).isEqualTo(request);
            assertThat(item).containsEntry("decisionNote", NOTE).containsEntry("decidedBy", (int) manager);
        });
        ResponseEntity<Map<String, Object>> schedule = call(HttpMethod.GET,
                MINE + "?from=2076-03-01&to=2076-03-31", staffToken);
        assertThat(listOf(schedule)).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("pendingRequestId", null).containsEntry("status", "SCHEDULED"));
    }

    @Test
    void afterARejectionTheStaffMemberCanSubmitAgainBecauseOnlyPendingRequestsAreUnique() {
        long first = pendingRequest(assignment, staff);
        assertThat(reject(first, NOTE).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> again = call(HttpMethod.POST, REQUESTS, staffToken, leaveBody(assignment));

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(requestCountOf(assignment)).isEqualTo(2);
        assertThat(fullRequestRow(first)).containsEntry("status", "REJECTED").containsEntry("decision_note", NOTE);
    }

    @Test
    void aRequestStuckOnAShiftThatAlreadyStartedCanStillBeRejectedToClearTheQueue() {
        long past = data.assignment(branch, staff, morning, LocalDate.of(2076, 3, 10), "SCHEDULED", "SELF", null);
        long request = pendingRequest(past, staff);

        ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED");
        assertThat(assignmentRow(past)).containsEntry("status", "SCHEDULED");
    }

    @Test
    void aDisabledRequesterIsStillNotifiedBecauseTheRowsAreHistoryNotAnAccountAction() {
        long request = pendingRequest(assignment, staff);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", staff);

        ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(rejectedOutbox(staff)).hasSize(2);
    }

    // ---------------------------------------------------------------- 409: không còn PENDING

    @Test
    void everyClosedStatusIs409AndLeavesNoTrace() {
        for (String status : List.of("APPROVED", "REJECTED", "WITHDRAWN")) {
            long request = data.leaveRequest(assignment, staff, status, manager);
            String before = rejectFootprint(request, assignment, staff);

            ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

            assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
            assertThat(message(response)).contains("yêu cầu nghỉ / đổi ca");
            assertThat(rejectFootprint(request, assignment, staff)).as(status).isEqualTo(before);
            assertThat(fullRequestRow(request)).containsEntry("status", status);
        }
    }

    @Test
    void rejectingTwiceIs409AndTheSecondCallKeepsTheFirstNoteAndWritesNothing() {
        long request = pendingRequest(assignment, staff);
        assertThat(reject(request, NOTE).getStatusCode().value()).isEqualTo(200);
        String after = rejectFootprint(request, assignment, staff);

        ResponseEntity<Map<String, Object>> second = reject(request, NOTE_PREFIX + " Lý do khác");

        assertEnvelope(second, 409, "INVALID_STATE_TRANSITION");
        assertThat(rejectFootprint(request, assignment, staff)).isEqualTo(after);
        assertThat(rejectAuditCount(request)).isEqualTo(1);
        assertThat(rejectedOutbox(staff)).hasSize(2);
    }

    @Test
    void aRequestTheSystemClosedWhenTheManagerCancelledTheAssignmentIs409AndKeepsTheSystemNote() {
        long request = pendingRequest(assignment, staff);
        assertThat(call(HttpMethod.POST, cancelPath(assignment), managerToken, reasonBody("Quản lý hủy"))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> response = reject(request, NOTE);

        assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null)
                .containsEntry("decision_note", "Phân ca đã bị quản lý hủy");
        assertThat(rejectAuditCount(request)).isZero();
    }

    @Test
    void aWithdrawAfterTheRejectionIs409AndACancelOfTheAssignmentLeavesTheManagersNoteAlone() {
        long request = pendingRequest(assignment, staff);
        assertThat(reject(request, NOTE).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> withdraw = call(HttpMethod.POST, withdrawPath(request), staffToken);
        ResponseEntity<Map<String, Object>> cancel = call(HttpMethod.POST, cancelPath(assignment), managerToken,
                reasonBody("Quản lý hủy"));

        assertEnvelope(withdraw, 409, "INVALID_STATE_TRANSITION");
        assertThat(cancel.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
    }

    @Test
    void disablingTheRequesterAfterARejectionClosesOnlyTheStillPendingRequestsAndKeepsTheManagersNote() {
        long rejected = pendingRequest(assignment, staff);
        assertThat(reject(rejected, NOTE).getStatusCode().value()).isEqualTo(200);
        long other = data.assignment(branch, staff, afternoon, DAY.plusDays(1), "SCHEDULED", "SELF", null);
        long stillPending = pendingRequest(other, staff);
        long superManager = data.staff("SUPER_MANAGER", null);

        ResponseEntity<Map<String, Object>> disabled = call(HttpMethod.POST, "/api/staff/" + staff + "/disable",
                token(superManager));

        assertThat(disabled.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(rejected)).as("BR-NS-09 chỉ đóng yêu cầu còn PENDING")
                .containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", NOTE);
        assertThat(fullRequestRow(stillPending)).containsEntry("status", "REJECTED").containsEntry("decided_by", null);
        assertThat(rejectAuditCount(rejected)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 400: lý do

    @Test
    void aMissingNullBlankOrTooLongNoteIs400ValidationFailedNamingTheFieldAndLeavesNoTrace() {
        long request = pendingRequest(assignment, staff);
        String before = rejectFootprint(request, assignment, staff);

        // U+3000 và U+2003: @NotBlank của Hibernate Validator để lọt, strip() làm rỗng; nếu DTO không chặn thì entity
        // ném IllegalArgumentException và request trả 500.
        for (String json : List.of("{}", "{'decisionNote':null}", "{'decisionNote':''}", "{'decisionNote':'   '}",
                rejectBody("　"), rejectBody("  　"), rejectBody(" "), rejectBody("x".repeat(301)),
                rejectBody(" ".repeat(10) + "x".repeat(290) + " ".repeat(10)))) {
            ResponseEntity<Map<String, Object>> response = rawReject(request, json);

            assertEnvelope(response, 400, "VALIDATION_FAILED");
            assertThat(message(response)).contains("decisionNote");
        }
        assertThat(rejectFootprint(request, assignment, staff)).isEqualTo(before);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
    }

    @Test
    void aNoteOfExactly300CharactersAndOneWithPaddingThatStripsToLessAreAcceptedAndStoredStripped() {
        long first = pendingRequest(assignment, staff);
        String limit = NOTE_PREFIX + "x".repeat(297);
        assertThat(limit).hasSize(300);

        ResponseEntity<Map<String, Object>> exact = reject(first, limit);

        assertThat(exact.getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(first)).containsEntry("decision_note", limit);

        long other = data.assignment(branch, staff, afternoon, DAY.plusDays(2), "SCHEDULED", "SELF", null);
        long second = pendingRequest(other, staff);
        ResponseEntity<Map<String, Object>> padded = reject(second, "  " + NOTE + "  ");

        assertThat(padded.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(padded)).containsEntry("decisionNote", NOTE);
        assertThat(fullRequestRow(second)).containsEntry("decision_note", NOTE);
        assertThat(rejectedOutbox(staff)).as("hai lần từ chối, mỗi lần một cặp thông báo").hasSize(4);
    }

    @Test
    void aBodyThatIsNotJsonIsMissingOrHasTheWrongShapeIsMalformed400AndLeavesNoTrace() {
        long request = pendingRequest(assignment, staff);
        String before = rejectFootprint(request, assignment, staff);

        for (String json : List.of("khong phai json", "[]", "{'decisionNote':", "'chuỗi'",
                "{'decisionNote':['x']}", "{'decisionNote':{'a':1}}")) {
            assertEnvelope(rawReject(request, json), 400, "MALFORMED_REQUEST");
        }
        assertEnvelope(call(HttpMethod.POST, rejectPath(request), managerToken), 400, "MALFORMED_REQUEST");
        assertThat(rejectFootprint(request, assignment, staff)).isEqualTo(before);
    }

    @Test
    void aNumberOrBooleanNoteIsNeverA5xxWhateverJacksonDecidesToDoWithIt() {
        long request = pendingRequest(assignment, staff);

        ResponseEntity<Map<String, Object>> response = rawReject(request, "{'decisionNote':123}");

        // Jackson mặc định ép số vô hướng sang String: ghi lại kết quả thực tế (không đổi cấu hình, application.yml:27).
        if (response.getStatusCode().value() == 200) {
            assertThat(dataOf(response)).containsEntry("decisionNote", "123");
        } else {
            assertEnvelope(response, 400, "MALFORMED_REQUEST");
            assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
        }
    }

    @Test
    void extraBodyFieldsCannotChangeTheStatusTheDeciderTheRequesterOrTheBranch() {
        long request = pendingRequest(assignment, staff);
        long otherBranch = data.branch();

        ResponseEntity<Map<String, Object>> response = rawReject(request,
                "{'decisionNote':'" + NOTE + "','status':'APPROVED','decidedBy':1,'requesterId':1,'branchId':"
                        + otherBranch + ",'closedAt':'2000-01-01T00:00:00Z'}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("status", "REJECTED").containsEntry("decidedBy", (int) manager)
                .containsEntry("requesterId", (int) staff);
        assertThat(Instant.parse((String) dataOf(response).get("closedAt"))).isEqualTo(NOW);
        assertThat(assignmentRow(assignment)).containsEntry("branch_id", branch);
    }

    // ---------------------------------------------------------------- 404 và id sai

    @Test
    void anUnknownRequestIs404AndWritesNothing() {
        int audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_REQUEST_REJECTED'", Integer.class);

        for (long id : new long[] {0L, Long.MAX_VALUE - 1}) {
            ResponseEntity<Map<String, Object>> response = reject(id, NOTE);

            assertEnvelope(response, 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_REQUEST_REJECTED'",
                Integer.class)).isEqualTo(audits);
    }

    @Test
    void aNonNumericOrFractionalIdIsMalformed400() {
        for (String id : new String[] {"abc", "1.5", "99999999999999999999"}) {
            assertEnvelope(call(HttpMethod.POST, LIST_BRANCH + "/" + id + "/reject", managerToken, rejectBody(NOTE)),
                    400, "MALFORMED_REQUEST");
        }
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> reject(long requestId, String note) {
        return rawReject(requestId, rejectBody(note));
    }

    private ResponseEntity<Map<String, Object>> rawReject(long requestId, String json) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, rejectPath(requestId), managerToken,
                json);
        seen.add(response);
        return response;
    }

    private void assertEnvelope(ResponseEntity<Map<String, Object>> response, int status, String errorCode) {
        seen.add(response);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().keySet()).containsExactlyInAnyOrderElementsOf(ENVELOPE);
        assertThat(errorCode(response)).isEqualTo(errorCode);
        assertThat(response.getBody()).containsEntry("success", false).containsEntry("statusCode", status);
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return contentOf(dataOf(response)).stream().map(item -> idOf(item, "shiftRequestId")).toList();
    }

    /** Bất biến: mọi yêu cầu do quản lý từ chối của branch này có đúng một dòng audit, và ngược lại. */
    private void assertRejectedRequestsHaveExactlyOneAudit() {
        List<Long> wrong = jdbc.queryForList("""
                SELECT r.id FROM shift_requests r JOIN shift_assignments a ON a.id = r.assignment_id
                WHERE a.branch_id = ? AND r.status = 'REJECTED' AND r.decided_by IS NOT NULL
                  AND (SELECT COUNT(*) FROM audit_logs l WHERE l.entity_type = 'shift_requests' AND l.entity_id = r.id
                       AND l.action = 'SHIFT_REQUEST_REJECTED') <> 1""", Long.class, branch);
        assertThat(wrong).as("yêu cầu bị quản lý từ chối mà không có đúng một audit").isEmpty();
        List<Long> orphan = jdbc.queryForList("""
                SELECT l.entity_id FROM audit_logs l JOIN shift_requests r ON r.id = l.entity_id
                JOIN shift_assignments a ON a.id = r.assignment_id
                WHERE a.branch_id = ? AND l.entity_type = 'shift_requests' AND l.action = 'SHIFT_REQUEST_REJECTED'
                  AND r.status <> 'REJECTED'""", Long.class, branch);
        assertThat(orphan).as("audit từ chối của yêu cầu không ở REJECTED").isEmpty();
    }
}
