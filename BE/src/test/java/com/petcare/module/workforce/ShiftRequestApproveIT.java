package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Duyệt yêu cầu nghỉ / đổi ca (workforce-v1 #13; docs/adr/0150, N3-S5a / S5b) trên Postgres thật. {@code LEAVE}: yêu cầu →
 * {@code APPROVED} (Yêu cầu#3) và phân ca → {@code ON_LEAVE} (Phân ca#3) cùng một mốc giờ, hai dòng audit, một cặp thông
 * báo khớp mẫu V40, POST = GET; hệ quả của {@code ON_LEAVE} (không còn chặn chồng giờ / ngừng dùng ca, listener BR-NS-09
 * không đụng). {@code SWAP}: phân ca đổi sang ca đích và vẫn {@code SCHEDULED} (Phân ca#5), yêu cầu vẫn nêu ca gốc
 * (V60), audit {@code SHIFT_ASSIGNMENT_SWAPPED}, ba lỗi 400 của ca đích (đã bắt đầu, ngừng dùng, chồng giờ) không để lại
 * dấu vết, 403 không khóa ca đích. Biên giờ bắt đầu ca (đúng giờ bắt đầu là đã bắt đầu); 409 đứng trước 400; toàn bộ ma
 * trận xác thực / phân quyền / validate của endpoint không có body (401, BR-TK-17, bảy vai trò, phạm vi chi nhánh, id
 * sai, 405, body bị bỏ qua). Era <b>năm 2080</b>; đồng hồ bắt đầu ở 07:59:59 giờ Việt Nam, một giây trước ca 08:00.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestApproveIT.TestBeans.class})
class ShiftRequestApproveIT extends ShiftRequestItSupport {

    /** 07:59:59 giờ Việt Nam (UTC+7) ngày 14/3/2080: một giây trước ca sáng 08:00. */
    private static final Instant NOW = Instant.parse("2080-03-14T00:59:59Z");
    private static final LocalDate TODAY = LocalDate.of(2080, 3, 14);
    private static final LocalDate DAY = LocalDate.of(2080, 3, 21);
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
    private long branchB;
    private long manager;
    private long managerB;
    private long staff;
    private long staffB;
    private long morning;
    private long afternoon;
    private long assignment;
    private long request;
    private long requestB;
    private String managerToken;
    private String staffToken;
    private int offset = 1;
    private final List<ResponseEntity<Map<String, Object>>> seen = new ArrayList<>();
    private final List<long[]> approved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        staff = data.staff("RECEPTIONIST", branch);
        staffB = data.staff("RECEPTIONIST", branchB);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        long morningB = definition(branchB, "Ca sáng B", "08:00", "12:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        requestB = pendingRequest(data.assignment(branchB, staffB, morningB, DAY, "SCHEDULED", "SELF", null), staffB);
        managerToken = token(manager);
        staffToken = token(staff);
    }

    @AfterEach
    void tearDown() {
        try {
            assertThat(seen).as("không có lỗi đầu vào nào được phép ra 5xx")
                    .allSatisfy(response -> assertThat(response.getStatusCode().is5xxServerError()).isFalse());
            assertEveryApprovalLeftBothAudits();
        } finally {
            purgeApprovedOutbox(staff, staffB);
            purgeRejectedOutbox(staff, staffB);
            purgeCancelOutbox(staff, staffB);
            purgeAbsenceOutbox(staff, staffB);
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void approveReturns200WithTheFifteenKeysAndMovesTheRequestToApprovedAndTheAssignmentToOnLeave() {
        Map<String, Object> assignmentBefore = assignmentRow(assignment);

        ResponseEntity<Map<String, Object>> response = approve(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(message(response)).isEqualTo("Đã duyệt yêu cầu");
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(body).containsEntry("shiftRequestId", (int) request).containsEntry("status", "APPROVED")
                .containsEntry("decidedBy", (int) manager).containsEntry("decisionNote", null)
                .containsEntry("requesterId", (int) staff).containsEntry("type", "LEAVE")
                .containsEntry("reason", "Việc gia đình").containsEntry("targetShiftDefinitionId", null)
                .containsEntry("targetShiftName", null);
        assertThat((String) body.get("currentShiftName")).as("ca gốc vẫn đúng sau khi duyệt nghỉ").startsWith("Ca sáng");
        assertThat(Instant.parse((String) body.get("closedAt"))).isEqualTo(NOW);

        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", null);
        assertThat(fullRequestRow(request).get("closed_at")).isNotNull();
        Map<String, Object> row = assignmentRow(assignment);
        assertThat(row).containsEntry("status", "ON_LEAVE").containsEntry("reason", "Việc gia đình")
                .containsEntry("status_changed_by", manager).containsEntry("shift_definition_id", morning)
                .containsEntry("work_date", assignmentBefore.get("work_date"))
                .containsEntry("branch_id", branch).containsEntry("staff_id", staff).containsEntry("source", "SELF");
        assertThat(row.get("status_changed_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT a.status_changed_at = r.closed_at FROM shift_assignments a
                JOIN shift_requests r ON r.assignment_id = a.id WHERE r.id = ?""", Boolean.class, request))
                .as("một mốc giờ cho cả hai dòng").isTrue();
    }

    @Test
    void approveWritesTwoAuditsEachWithBeforeAfterTheRequestReasonAndTheManagerAsActor() {
        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        assertThat(approveRequestAuditCount(request)).isEqualTo(1);
        assertThat(onLeaveAuditCount(assignment)).isEqualTo(1);
        Map<String, Object> requestAudit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'shift_requests' AND entity_id = ?
                  AND action = 'SHIFT_REQUEST_APPROVED'""", request);
        assertThat(requestAudit).containsEntry("actor_account_id", manager).containsEntry("reason", "Việc gia đình");
        assertThat((String) requestAudit.get("before_data")).contains("\"status\": \"PENDING\"")
                .contains("\"requesterId\": " + staff).doesNotContain("APPROVED")
                .doesNotContain("\"decidedBy\": " + manager);
        assertThat((String) requestAudit.get("after_data")).contains("\"status\": \"APPROVED\"")
                .contains("\"decidedBy\": " + manager).contains("\"requesterId\": " + staff);
        Map<String, Object> assignmentAudit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'shift_assignments' AND entity_id = ?
                  AND action = 'SHIFT_ASSIGNMENT_ON_LEAVE'""", assignment);
        assertThat(assignmentAudit).containsEntry("actor_account_id", manager)
                .containsEntry("reason", "Việc gia đình");
        assertThat((String) assignmentAudit.get("before_data")).contains("\"status\": \"SCHEDULED\"")
                .contains("\"source\": \"SELF\"").doesNotContain("ON_LEAVE").doesNotContain("Việc gia đình");
        assertThat((String) assignmentAudit.get("after_data")).contains("\"status\": \"ON_LEAVE\"")
                .contains("\"reason\": \"Việc gia đình\"").contains("\"staffId\": " + staff)
                .contains("\"shiftDefinitionId\": " + morning);
    }

    @Test
    void approveNotifiesTheRequesterInAppThenByEmailAndThePayloadFitsTheRealTemplates() {
        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> outbox = approvedOutbox(staff);
        assertThat(outbox).hasSize(2);
        assertThat(outbox.get(0)).containsEntry("channel", "IN_APP").containsEntry("template_code", APPROVED_APP)
                .containsEntry("recipient_account_id", staff).containsEntry("recipient_email", null);
        assertThat(outbox.get(1)).containsEntry("channel", "EMAIL").containsEntry("template_code", APPROVED_MAIL)
                .containsEntry("recipient_email", accountEmail(staff)).containsEntry("recipient_account_id", null);
        assertOutboxFitsTemplate(APPROVED_APP, staff);
        assertOutboxFitsTemplate(APPROVED_MAIL, staff);

        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String staffName = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?",
                String.class, staff);
        for (String template : List.of(APPROVED_APP, APPROVED_MAIL)) {
            assertThat(outboxPayloadField(template, staff, "mo_ta_yeu_cau")).startsWith("nghỉ ca Ca sáng ")
                    .endsWith(" ngày 21/03/2080");
            assertThat(outboxPayloadField(template, staff, "chi_nhanh")).isEqualTo(branchName);
            assertThat(outboxPayloadField(template, staff, "ly_do")).as("mẫu duyệt không có lý do").isNull();
        }
        assertThat(outboxPayloadField(APPROVED_MAIL, staff, "ten_nhan_vien")).isEqualTo(staffName);
        assertThat(outboxPayloadField(APPROVED_APP, staff, "ten_nhan_vien"))
                .as("ngoài required_vars của bản trong app").isNull();
    }

    @Test
    void theAnswerOfThePostIsExactlyTheRowTheBranchListAndMyListShowAfterwards() {
        Map<String, Object> posted = dataOf(approve(request));

        ResponseEntity<Map<String, Object>> listed = call(HttpMethod.GET, LIST_BRANCH + "?status=APPROVED",
                managerToken);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = contentOf(dataOf(listed)).stream()
                .filter(item -> idOf(item, "shiftRequestId") == request).findFirst().orElseThrow();
        assertThat(row).as("POST = GET, kể cả closedAt (cắt micro giây)").isEqualTo(posted);

        ResponseEntity<Map<String, Object>> mine = call(HttpMethod.GET, REQUESTS + "?status=APPROVED", staffToken);
        assertThat(contentOf(dataOf(mine))).singleElement().isEqualTo(posted);
    }

    @Test
    void afterwardsTheQueueIsEmptyAndTheScheduleShowsTheAssignmentOnLeaveWithNoPendingRequest() {
        assertThat(ids(call(HttpMethod.GET, LIST_BRANCH, managerToken))).containsExactly(request);

        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        assertThat(ids(call(HttpMethod.GET, LIST_BRANCH, managerToken))).as("hàng chờ mặc định = PENDING").isEmpty();
        ResponseEntity<Map<String, Object>> schedule = call(HttpMethod.GET,
                MINE + "?from=2080-03-01&to=2080-03-31", staffToken);
        assertThat(listOf(schedule)).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("pendingRequestId", null).containsEntry("status", "ON_LEAVE")
                .containsEntry("reason", "Việc gia đình"));
    }

    @Test
    void anOnLeaveAssignmentNoLongerBlocksAnOverlappingManagerAssignmentThatWas400Before() {
        long overlapping = definition(branch, "Ca ngắn", "09:00", "11:00", true);
        ResponseEntity<Map<String, Object>> before = call(HttpMethod.POST, ASSIGNMENTS, managerToken,
                body(staff, overlapping, DAY.toString()));
        assertBusinessRule(before, "BR-NS-02");

        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> after = call(HttpMethod.POST, ASSIGNMENTS, managerToken,
                body(staff, overlapping, DAY.toString()));
        assertThat(after.getStatusCode().value()).as("chỉ phân ca SCHEDULED mới tính chồng giờ (BR-NS-02)")
                .isEqualTo(201);
    }

    @Test
    void anOnLeaveAssignmentNoLongerBlocksDeactivatingItsShiftThatWas400Before() {
        ResponseEntity<Map<String, Object>> before = call(HttpMethod.PATCH, PATH + "/" + morning, managerToken,
                "{'active':false}");
        assertBusinessRule(before, "BR-NS-01");

        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> after = call(HttpMethod.PATCH, PATH + "/" + morning, managerToken,
                "{'active':false}");
        assertThat(after.getStatusCode().value()).as("chỉ phân ca SCHEDULED mới chặn ngừng dùng ca (BR-NS-01)")
                .isEqualTo(200);
    }

    @Test
    void disablingTheRequesterAfterTheApprovalKeepsTheAssignmentOnLeaveAndTheRequestApprovedAndSendsNoCancelNotice() {
        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);
        long superManager = data.staff("SUPER_MANAGER", null);

        ResponseEntity<Map<String, Object>> disabled = call(HttpMethod.POST, "/api/staff/" + staff + "/disable",
                token(superManager));

        assertThat(disabled.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(assignment)).as("BR-NS-09 chỉ hủy phân ca SCHEDULED")
                .containsEntry("status", "ON_LEAVE").containsEntry("status_changed_by", manager);
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
        assertThat(cancelOutbox(staff)).isEmpty();
    }

    @Test
    void aDisabledRequesterIsStillNotifiedBecauseTheRowsAreHistoryNotAnAccountAction() {
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", staff);

        ResponseEntity<Map<String, Object>> response = approve(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(approvedOutbox(staff)).hasSize(2);
    }

    @Test
    void approveDoesNotRecheckTheTwentyFourHourLeadWindowOnlyThatTheShiftHasNotStarted() {
        // 07:59:59 hôm nay, ca chiều 13:00 hôm nay: còn khoảng 5 giờ, ít hơn 24 giờ [CFG] của lúc gửi (D074a).
        long today = data.assignment(branch, staff, afternoon, TODAY, "SCHEDULED", "MANAGER", null);
        long todayRequest = pendingRequest(today, staff);

        ResponseEntity<Map<String, Object>> response = approve(todayRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(today)).containsEntry("status", "ON_LEAVE");
    }

    // ---------------------------------------------------------------- 400: ca đã bắt đầu (biên theo đồng hồ)

    @Test
    void oneSecondBeforeTheStartItWorksExactlyAtTheStartAndAfterItIs400BrNs06() {
        long early = data.staff("RECEPTIONIST", branch);
        long late = data.staff("RECEPTIONIST", branch);
        long earlyRequest = pendingRequest(data.assignment(branch, early, morning, TODAY, "SCHEDULED", "SELF", null),
                early);
        long lateAssignment = data.assignment(branch, late, morning, TODAY, "SCHEDULED", "SELF", null);
        long lateRequest = pendingRequest(lateAssignment, late);
        try {
            assertThat(approve(earlyRequest).getStatusCode().value()).as("07:59:59, ca 08:00").isEqualTo(200);

            String before = approveFootprint(lateRequest, lateAssignment, late);
            for (long seconds : new long[] {1, 2, 3600}) {
                clock.set(NOW.plusSeconds(seconds));
                ResponseEntity<Map<String, Object>> response = approve(lateRequest);

                assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
                assertThat(message(response)).as("+%ds", seconds).isEqualTo("Ca đã bắt đầu, không duyệt được (BR-NS-06)");
            }
            assertThat(approveFootprint(lateRequest, lateAssignment, late)).isEqualTo(before);
            assertThat(fullRequestRow(lateRequest)).containsEntry("status", "PENDING");
        } finally {
            purgeApprovedOutbox(early, late);
        }
    }

    @Test
    void aRequestStuckOnAShiftOfAPastDayIs400BrNs06AndCanStillBeRejectedToClearTheQueue() {
        long past = data.assignment(branch, staff, afternoon, LocalDate.of(2080, 3, 10), "SCHEDULED", "SELF", null);
        long pastRequest = pendingRequest(past, staff);
        String before = approveFootprint(pastRequest, past, staff);

        ResponseEntity<Map<String, Object>> response = approve(pastRequest);

        assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
        assertThat(message(response)).endsWith("(BR-NS-06)");
        assertThat(approveFootprint(pastRequest, past, staff)).isEqualTo(before);
        ResponseEntity<Map<String, Object>> rejected = call(HttpMethod.POST, rejectPath(pastRequest), managerToken,
                rejectBody(NOTE));
        assertThat(rejected.getStatusCode().value()).as("từ chối vẫn dọn được hàng chờ").isEqualTo(200);
    }

    // ---------------------------------------------------------------- 409: không còn PENDING

    @Test
    void everyClosedStatusIs409WithTheMachineNameAndLeavesNoTrace() {
        for (String status : List.of("APPROVED", "REJECTED", "WITHDRAWN")) {
            long closed = data.leaveRequest(assignment, staff, status, manager);
            String before = approveFootprint(closed, assignment, staff);

            ResponseEntity<Map<String, Object>> response = approve(closed);

            assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
            assertThat(message(response)).isEqualTo("Không thể chuyển trạng thái yêu cầu nghỉ / đổi ca từ " + status
                    + " sang APPROVED");
            assertThat(approveFootprint(closed, assignment, staff)).as(status).isEqualTo(before);
        }
        assertThat(assignmentRow(assignment)).containsEntry("status", "SCHEDULED");
    }

    @Test
    void approvingTwiceIs409AndTheSecondCallWritesNothingMore() {
        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);
        String after = approveFootprint(request, assignment, staff);

        ResponseEntity<Map<String, Object>> second = approve(request);

        assertEnvelope(second, 409, "INVALID_STATE_TRANSITION");
        assertThat(approveFootprint(request, assignment, staff)).isEqualTo(after);
        assertThat(approveRequestAuditCount(request)).isEqualTo(1);
        assertThat(onLeaveAuditCount(assignment)).isEqualTo(1);
        assertThat(approvedOutbox(staff)).hasSize(2);
    }

    @Test
    void aClosedRequestOnAShiftThatAlreadyStartedIs409NotBr400BecauseTheStateCheckComesFirst() {
        long past = data.assignment(branch, staff, afternoon, LocalDate.of(2080, 3, 10), "SCHEDULED", "SELF", null);
        long closed = data.leaveRequest(past, staff, "WITHDRAWN", null);

        ResponseEntity<Map<String, Object>> response = approve(closed);

        assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    void aRequestTheSystemClosedWhenTheManagerCancelledTheAssignmentIs409AndTheAssignmentStaysCancelled() {
        assertThat(call(HttpMethod.POST, cancelPath(assignment), managerToken, reasonBody("Quản lý hủy"))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> response = approve(request);

        assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null);
        assertThat(assignmentRow(assignment)).containsEntry("status", "CANCELLED");
        assertThat(onLeaveAuditCount(assignment)).isZero();
        assertThat(approvedOutbox(staff)).isEmpty();
    }

    @Test
    void afterTheApprovalTheRequestAndTheOnLeaveAssignmentAcceptNoFurtherTransition() {
        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> withdraw = call(HttpMethod.POST, withdrawPath(request), staffToken);
        ResponseEntity<Map<String, Object>> reject = call(HttpMethod.POST, rejectPath(request), managerToken,
                rejectBody(NOTE));
        ResponseEntity<Map<String, Object>> cancel = call(HttpMethod.POST, cancelPath(assignment), managerToken,
                reasonBody("Quản lý hủy"));
        long past = data.assignment(branch, staff, afternoon, LocalDate.of(2080, 3, 10), "ON_LEAVE", "SELF", manager);
        ResponseEntity<Map<String, Object>> absent = call(HttpMethod.POST, absentPath(past), managerToken,
                reasonBody("Báo ốm"));

        for (ResponseEntity<Map<String, Object>> response : List.of(withdraw, reject, cancel, absent)) {
            assertEnvelope(response, 409, "INVALID_STATE_TRANSITION");
        }
        assertThat(assignmentRow(assignment)).containsEntry("status", "ON_LEAVE");
        assertThat(fullRequestRow(request)).containsEntry("status", "APPROVED").containsEntry("decided_by", manager);
    }

    // ---------------------------------------------------------------- SWAP (N3-S5b, ADR-0150 mục 17–22)

    /** Một yêu cầu SWAP {@code PENDING} của {@code staffId} từ {@code from} sang {@code to} ngày {@code day}. */
    private long[] pendingSwap(long staffId, long from, long to, LocalDate day) {
        long swapAssignment = data.assignment(branch, staffId, from, day, "SCHEDULED", "SELF", null);
        return new long[] {data.swapRequest(swapAssignment, staffId, to, "PENDING", null), swapAssignment};
    }

    @Test
    void approveOfASwapMovesTheAssignmentToTheTargetKeepsItScheduledAndTheAnswerStillNamesTheOriginalShift() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));

        ResponseEntity<Map<String, Object>> response = approve(swap[0]);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(body).containsEntry("status", "APPROVED").containsEntry("type", "SWAP")
                .containsEntry("decidedBy", (int) manager).containsEntry("decisionNote", null)
                .containsEntry("targetShiftDefinitionId", (int) afternoon);
        assertThat((String) body.get("currentShiftName")).as("ca gốc, không phải ca đang gắn (D074-b)")
                .startsWith("Ca sáng");
        assertThat((String) body.get("targetShiftName")).startsWith("Ca chiều");
        assertThat(Instant.parse((String) body.get("closedAt"))).isEqualTo(NOW);

        assertThat(fullRequestRow(swap[0])).containsEntry("status", "APPROVED").containsEntry("decided_by", manager)
                .containsEntry("current_shift_definition_id", morning)
                .containsEntry("target_shift_definition_id", afternoon);
        assertThat(assignmentRow(swap[1])).as("Phân ca#5: chỉ đổi ca, không đổi trạng thái")
                .containsEntry("shift_definition_id", afternoon).containsEntry("status", "SCHEDULED")
                .containsEntry("reason", null).containsEntry("status_changed_by", null)
                .containsEntry("status_changed_at", null).containsEntry("source", "SELF")
                .containsEntry("work_date", DAY.plusDays(1).toString());
    }

    @Test
    void afterASwapThePostEqualsBothListsAndTheScheduleShowsTheTargetShiftWithNoPendingRequest() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));
        Map<String, Object> posted = dataOf(approve(swap[0]));

        Map<String, Object> listed = contentOf(dataOf(call(HttpMethod.GET, LIST_BRANCH + "?status=APPROVED",
                managerToken))).stream().filter(item -> idOf(item, "shiftRequestId") == swap[0]).findFirst()
                .orElseThrow();
        assertThat(listed).as("POST = GET của chi nhánh").isEqualTo(posted);
        Map<String, Object> mine = contentOf(dataOf(call(HttpMethod.GET, REQUESTS + "?status=APPROVED", staffToken)))
                .stream().filter(item -> idOf(item, "shiftRequestId") == swap[0]).findFirst().orElseThrow();
        assertThat(mine).as("POST = GET của tôi").isEqualTo(posted);

        ResponseEntity<Map<String, Object>> schedule = call(HttpMethod.GET, MINE + "?from=" + DAY.plusDays(1) + "&to="
                + DAY.plusDays(1), staffToken);
        assertThat(listOf(schedule)).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("shiftDefinitionId", (int) afternoon).containsEntry("startTime", "13:00")
                .containsEntry("status", "SCHEDULED").containsEntry("pendingRequestId", null));
    }

    @Test
    void approveOfASwapWritesTheSwappedAuditWithOnlyTheShiftChangedAndTheRequestAuditAndTheExchangedNotice() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));

        assertThat(approve(swap[0]).getStatusCode().value()).isEqualTo(200);

        assertThat(approveRequestAuditCount(swap[0])).isEqualTo(1);
        assertThat(swapAuditCount(swap[1])).isEqualTo(1);
        assertThat(onLeaveAuditCount(swap[1])).isZero();
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, reason, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'shift_assignments' AND entity_id = ?
                  AND action = 'SHIFT_ASSIGNMENT_SWAPPED'""", swap[1]);
        assertThat(audit).containsEntry("actor_account_id", manager).containsEntry("reason", "Đổi ca");
        assertThat((String) audit.get("before_data")).contains("\"shiftDefinitionId\": " + morning)
                .contains("\"status\": \"SCHEDULED\"");
        assertThat((String) audit.get("after_data")).contains("\"shiftDefinitionId\": " + afternoon)
                .contains("\"status\": \"SCHEDULED\"").contains("\"staffId\": " + staff);

        List<Map<String, Object>> outbox = approvedOutbox(staff);
        assertThat(outbox).hasSize(2);
        assertOutboxFitsTemplate(APPROVED_APP, staff);
        assertOutboxFitsTemplate(APPROVED_MAIL, staff);
        for (String template : List.of(APPROVED_APP, APPROVED_MAIL)) {
            assertThat(outboxPayloadField(template, staff, "mo_ta_yeu_cau")).startsWith("đổi ca Ca sáng")
                    .contains(" sang ca Ca chiều").endsWith(" ngày 22/03/2080");
        }
    }

    @Test
    void aSwapToAShiftDeactivatedSinceTheRequestIs400BrNs06AndLeavesNoTrace() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));
        jdbc.update("UPDATE shift_definitions SET is_active = false WHERE id = ?", afternoon);
        String before = approveFootprint(swap[0], swap[1], staff);

        ResponseEntity<Map<String, Object>> response = approve(swap[0]);

        assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
        assertThat(message(response)).isEqualTo("Ca đích đã ngừng dùng (BR-NS-06)");
        assertThat(approveFootprint(swap[0], swap[1], staff)).isEqualTo(before);
        assertThat(canLockNowait("shift_definitions", afternoon)).as("rollback nhả khóa FOR SHARE").isTrue();
    }

    @Test
    void aSwapWhoseTargetNowOverlapsAnotherAssignmentIs400BrNs06ButAnAdjacentShiftDoesNotBlock() {
        long middle = definition(branch, "Ca giữa", "16:00", "18:00", true);
        long evening = definition(branch, "Ca tối", "17:00", "19:00", true);
        long[] blocked = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));
        long other = data.assignment(branch, staff, middle, DAY.plusDays(1), "SCHEDULED", "MANAGER", null);
        String before = approveFootprint(blocked[0], blocked[1], staff);

        ResponseEntity<Map<String, Object>> response = approve(blocked[0]);

        assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
        assertThat(message(response)).startsWith("Ca đích chồng giờ với phân ca khác trong ngày: Ca giữa")
                .contains("(16:00–18:00)").endsWith("(BR-NS-06)");
        assertThat(approveFootprint(blocked[0], blocked[1], staff)).isEqualTo(before);

        // Ca liền kề (17:00 = giờ kết thúc ca đích) không chồng; chính phân ca đang đổi cũng không tính.
        jdbc.update("UPDATE shift_assignments SET shift_definition_id = ? WHERE id = ?", evening, other);
        assertThat(approve(blocked[0]).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aSwapToAnEarlierShiftIs200OneSecondBeforeTheTargetStartsAnd400BrNs06FromThenOnEvenThoughTheCurrentHasNot() {
        long early = data.staff("RECEPTIONIST", branch);
        long late = data.staff("RECEPTIONIST", branch);
        long[] earlySwap = pendingSwap(early, afternoon, morning, TODAY);
        long[] lateSwap = pendingSwap(late, afternoon, morning, TODAY);
        try {
            assertThat(approve(earlySwap[0]).getStatusCode().value()).as("07:59:59, ca đích 08:00").isEqualTo(200);

            String before = approveFootprint(lateSwap[0], lateSwap[1], late);
            for (long seconds : new long[] {1, 3600}) {
                clock.set(NOW.plusSeconds(seconds));
                ResponseEntity<Map<String, Object>> response = approve(lateSwap[0]);

                assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
                assertThat(message(response)).as("+%ds", seconds)
                        .isEqualTo("Ca đích đã bắt đầu, không duyệt được (BR-NS-06)");
            }
            assertThat(approveFootprint(lateSwap[0], lateSwap[1], late)).isEqualTo(before);
            assertThat(assignmentRow(lateSwap[1])).containsEntry("shift_definition_id", afternoon);
        } finally {
            purgeApprovedOutbox(early, late);
        }
    }

    @Test
    void aSwapOfAnotherBranchIs403AndNeverLocksTheTargetShiftOrTheAccount() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));
        String before = approveFootprint(swap[0], swap[1], staff);

        ResponseEntity<Map<String, Object>> response = approveAs(token(managerB), swap[0]);

        assertEnvelope(response, 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(approveFootprint(swap[0], swap[1], staff)).isEqualTo(before);
        assertThat(canLockNowait("shift_definitions", afternoon)).isTrue();
        assertThat(canLockNowait("accounts", staff)).isTrue();
    }

    @Test
    void aSwapIsGuardedByTheSameRoleCheckAsALeave() {
        long[] swap = pendingSwap(staff, morning, afternoon, DAY.plusDays(1));
        String before = approveFootprint(swap[0], swap[1], staff);

        assertEnvelope(approveAs(staffToken, swap[0]), 403, "ACCESS_DENIED");
        assertEnvelope(approveAs(token(data.staff("SUPER_MANAGER", null)), swap[0]), 403, "ACCESS_DENIED");

        assertThat(approveFootprint(swap[0], swap[1], staff)).isEqualTo(before);
    }

    @Test
    void aSwapStillAnswers409WhenClosedAnd400WhenTheCurrentShiftAlreadyStarted() {
        long closedAssignment = data.assignment(branch, staff, morning, DAY.plusDays(2), "SCHEDULED", "SELF", null);
        long closed = data.swapRequest(closedAssignment, staff, afternoon, "REJECTED", null);
        long past = data.assignment(branch, staff, morning, LocalDate.of(2080, 3, 10), "SCHEDULED", "SELF", null);
        long started = data.swapRequest(past, staff, afternoon, "PENDING", null);

        ResponseEntity<Map<String, Object>> conflict = call(HttpMethod.POST, approvePath(closed), managerToken);
        ResponseEntity<Map<String, Object>> tooLate = call(HttpMethod.POST, approvePath(started), managerToken);

        assertEnvelope(conflict, 409, "INVALID_STATE_TRANSITION");
        assertEnvelope(tooLate, 400, "BUSINESS_RULE_VIOLATION");
        assertThat(message(tooLate)).endsWith("(BR-NS-06)");
    }

    // ---------------------------------------------------------------- 404 và id sai

    @Test
    void anUnknownRequestIs404AndWritesNothing() {
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_REQUEST_APPROVED'",
                Integer.class);

        for (long id : new long[] {0L, -1L, Long.MAX_VALUE - 1}) {
            ResponseEntity<Map<String, Object>> response = approve(id);

            assertEnvelope(response, 404, "RESOURCE_NOT_FOUND");
            assertThat(message(response)).isEqualTo("Không tìm thấy yêu cầu #" + id);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'SHIFT_REQUEST_APPROVED'",
                Integer.class)).isEqualTo(audits);
    }

    @Test
    void aNonNumericFractionalOrOverflowingIdIsMalformed400() {
        for (String id : new String[] {"abc", "1.5", "99999999999999999999"}) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, LIST_BRANCH + "/" + id + "/approve",
                    managerToken);

            assertEnvelope(response, 400, "MALFORMED_REQUEST");
            assertThat(message(response)).isEqualTo("Tham số 'shiftRequestId' sai kiểu dữ liệu");
        }
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenApproveIs401AndLeavesNoTrace() {
        String before = trace();
        for (String token : new String[] {null, "", "rac.khong.phai.token"}) {
            ResponseEntity<Map<String, Object>> response = approveAs(token, request);

            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aRevokedSessionALockedOrDisabledAccountAndAnExpiredAccessTokenAre401() {
        String before = trace();
        String revoked = token(manager);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", manager);
        assertThat(approveAs(revoked, request).getStatusCode().value()).isEqualTo(401);

        long lockedManager = data.staff("BRANCH_MANAGER", branch);
        String lockedToken = token(lockedManager);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", lockedManager);
        assertThat(approveAs(lockedToken, request).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", lockedManager);
        assertThat(approveAs(lockedToken, request).getStatusCode().value()).isEqualTo(401);

        long agingManager = data.staff("BRANCH_MANAGER", branch);
        String aging = token(agingManager);
        // Profile test đặt session.access_ttl_minutes = 720, nên qua mốc 10 phút chưa hết hạn: dời quá 12 giờ.
        clock.set(NOW.plus(Duration.ofHours(13)));
        assertThat(approveAs(aging, request).getStatusCode().value()).isEqualTo(401);

        assertThat(trace()).isEqualTo(before);
    }

    @Test
    void aTemporaryLoginLockDoesNotCutAnExistingSessionSoTheManagerCanStillApprove() {
        jdbc.update("UPDATE accounts SET locked_until = now() + interval '1 hour' WHERE id = ?", manager);

        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17BeforeTheIdIsBound() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", manager);
        String before = trace();

        assertBusinessRule(approve(request), "BR-TK-17");
        assertBusinessRule(call(HttpMethod.POST, LIST_BRANCH + "/abc/approve", managerToken), "BR-TK-17");
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- vai trò

    @Test
    void onlyABranchManagerMayApproveEveryOtherRoleIs403AndLeavesNoTrace() {
        long vet = data.staff("VET", branch);
        long caretaker = data.staff("CARETAKER", branch);
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        String before = trace();

        for (long denied : new long[] {staff, vet, caretaker, admin, superManager, customer}) {
            ResponseEntity<Map<String, Object>> response = approveAs(token(denied), request);

            assertThat(response.getStatusCode().value()).as("account=%d", denied).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
            assertThat(message(response)).isEqualTo("Không có quyền thực hiện thao tác này");
        }
        assertThat(trace()).as("kể cả chính người gửi cũng không tự duyệt được").isEqualTo(before);

        assertThat(approve(request).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aMalformedIdIs400EvenForARoleThatWouldBe403ButAWellFormedIdIs403() {
        ResponseEntity<Map<String, Object>> malformed = call(HttpMethod.POST, LIST_BRANCH + "/abc/approve", staffToken);
        ResponseEntity<Map<String, Object>> wellFormed = approveAs(staffToken, request);

        assertEnvelope(malformed, 400, "MALFORMED_REQUEST");
        assertThat(wellFormed.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(wellFormed)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void theRoleIsReadFromTheDatabaseOnEveryRequestSoAChangeTakesEffectImmediately() {
        assertThat(approveAs(staffToken, request).getStatusCode().value()).isEqualTo(403);

        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", staff);
        assertThat(approveAs(staffToken, request).getStatusCode().value()).as("được thăng chức giữa phiên")
                .isEqualTo(200);

        long second = pendingOnNewDay(staff);
        String managerSession = token(manager);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", manager);
        assertThat(approveAs(managerSession, second).getStatusCode().value()).as("bị giáng chức giữa phiên")
                .isEqualTo(403);
        assertThat(fullRequestRow(second)).containsEntry("status", "PENDING");
    }

    // ---------------------------------------------------------------- chi nhánh

    @Test
    void aManagerOfAnotherBranchGets403AndNeverLocksTheOtherBranchsAccounts() {
        String before = trace();

        ResponseEntity<Map<String, Object>> response = approveAs(token(managerB), request);

        assertEnvelope(response, 403, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(message(response)).isEqualTo("Không có quyền truy cập dữ liệu này");
        assertThat(trace()).isEqualTo(before);
        // Phạm vi kiểm bằng đọc không khóa trước mọi khóa: dòng tài khoản của nhân viên chi nhánh khác lấy được ngay.
        assertThat(canLockNowait("accounts", staff)).isTrue();
        assertThat(canLockNowait("shift_requests", request)).isTrue();
    }

    @Test
    void anotherBranchsRequestIs403EvenWhenItIsAlreadyClosedBecauseScopeComesBeforeTheStateCheck() {
        long closed = data.leaveRequest(assignment, staff, "WITHDRAWN", null);

        ResponseEntity<Map<String, Object>> response = approveAs(token(managerB), closed);

        assertEnvelope(response, 403, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void aManagerMovedToAnotherBranchMidSessionLosesTheOldBranchAtOnceWithTheSameToken() {
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, manager);

        assertThat(approve(request).getStatusCode().value()).isEqualTo(403);
        assertThat(approve(requestB).getStatusCode().value()).isEqualTo(200);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
    }

    @Test
    void aRequesterWhoMovedBranchLeavesTheRequestWithTheBranchOfTheAssignmentSoOnlyTheOldManagerCanApprove() {
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, staff);

        assertThat(approveAs(token(managerB), request).getStatusCode().value()).as("chi nhánh hiện tại của người gửi")
                .isEqualTo(403);
        assertThat(approve(request).getStatusCode().value()).as("chi nhánh của phân ca").isEqualTo(200);
    }

    @Test
    void aManagerOfADraftBranchMayStillApproveTheRequestsOfThatBranch() {
        long draft = data.branch();
        jdbc.update("UPDATE branches SET status = 'DRAFT' WHERE id = ?", draft);
        long draftManager = data.staff("BRANCH_MANAGER", draft);
        long draftStaff = data.staff("RECEPTIONIST", draft);
        long shift = definition(draft, "Ca D", "08:00", "12:00", true);
        long draftRequest = pendingRequest(data.assignment(draft, draftStaff, shift, DAY, "SCHEDULED", "SELF", null),
                draftStaff);

        try {
            assertThat(approveAs(token(draftManager), draftRequest).getStatusCode().value()).isEqualTo(200);
        } finally {
            purgeApprovedOutbox(draftStaff);
        }
    }

    @Test
    void aBranchIdInTheQueryOrTheBodyIsIgnored() {
        String token = token(manager);
        String before = trace();

        ResponseEntity<Map<String, Object>> viaQuery = call(HttpMethod.POST,
                approvePath(requestB) + "?branchId=" + branch, token);
        ResponseEntity<Map<String, Object>> viaBody = call(HttpMethod.POST, approvePath(requestB), token,
                "{'branchId':" + branch + "}");
        seen.add(viaQuery);
        seen.add(viaBody);

        assertThat(viaQuery.getStatusCode().value()).isEqualTo(403);
        assertThat(viaBody.getStatusCode().value()).isEqualTo(403);
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- không có body: mọi nội dung bị bỏ qua

    @Test
    void theEndpointReadsNoBodySoAnyContentTypeOrExtraFieldIsIgnoredAndTheDeciderIsAlwaysTheCaller() {
        long viaText = pendingOnNewDay(staff);
        long viaJson = pendingOnNewDay(staff);
        long viaJunk = pendingOnNewDay(staff);

        ResponseEntity<Map<String, Object>> text = post(approvePath(viaText), managerToken, MediaType.TEXT_PLAIN,
                "decisionNote=x");
        ResponseEntity<Map<String, Object>> json = call(HttpMethod.POST, approvePath(viaJson), managerToken,
                "{'decidedBy':" + managerB + ",'status':'REJECTED','decisionNote':'x','closedAt':'2000-01-01T00:00:00Z'}");
        ResponseEntity<Map<String, Object>> junk = post(approvePath(viaJunk), managerToken,
                MediaType.APPLICATION_JSON, "khong phai json");
        for (ResponseEntity<Map<String, Object>> response : List.of(text, json, junk)) {
            seen.add(response);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(dataOf(response)).containsEntry("status", "APPROVED").containsEntry("decidedBy", (int) manager)
                    .containsEntry("decisionNote", null);
            assertThat(Instant.parse((String) dataOf(response).get("closedAt"))).isEqualTo(NOW);
            approved.add(new long[] {((Number) dataOf(response).get("shiftRequestId")).longValue(),
                    ((Number) dataOf(response).get("shiftAssignmentId")).longValue()});
        }
    }

    @Test
    void aWrongMethodIs405AndLeavesNoTrace() {
        String before = trace();
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            // GET / DELETE không mang body (HttpURLConnection từ chối); PUT / PATCH mang body như một lần gọi thật.
            String json = method == HttpMethod.PUT || method == HttpMethod.PATCH ? "{'x':1}" : null;
            ResponseEntity<Map<String, Object>> response = call(method, approvePath(request), managerToken, json);

            assertThat(response.getStatusCode().value()).as(method.name()).isEqualTo(405);
            assertThat(errorCode(response)).isEqualTo("METHOD_NOT_ALLOWED");
        }
        assertThat(trace()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> approve(long requestId) {
        return approveAs(managerToken, requestId);
    }

    private ResponseEntity<Map<String, Object>> approveAs(String token, long requestId) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, approvePath(requestId), token);
        seen.add(response);
        if (response.getStatusCode().value() == 200) {
            approved.add(new long[] {requestId, ((Number) dataOf(response).get("shiftAssignmentId")).longValue()});
        }
        return response;
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, MediaType type, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    /** Một yêu cầu {@code LEAVE} {@code PENDING} mới trên một ngày chưa dùng của nhân viên (ca sáng). */
    private long pendingOnNewDay(long staffId) {
        return pendingRequest(data.assignment(branch, staffId, morning, DAY.plusDays(10L + offset++), "SCHEDULED",
                "SELF", null), staffId);
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

    private boolean canLockNowait(String table, long id) {
        try {
            tx.execute(status -> jdbc.queryForObject(
                    "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", Long.class, id));
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    /** Dấu vết của hai yêu cầu cố định (chi nhánh A và B) để chứng minh một lệnh bị từ chối không để lại gì. */
    private String trace() {
        return approveFootprint(request, assignment, staff) + "#" + approveFootprint(requestB,
                jdbc.queryForObject("SELECT assignment_id FROM shift_requests WHERE id = ?", Long.class, requestB),
                staffB);
    }

    /**
     * Bất biến: mọi yêu cầu duyệt thành công qua API có đúng một audit của yêu cầu và đúng một audit của phân ca theo
     * loại: {@code LEAVE} → {@code ON_LEAVE} (phân ca {@code ON_LEAVE}); {@code SWAP} → {@code SWAPPED} (phân ca vẫn
     * {@code SCHEDULED}, đã ở ca đích).
     */
    private void assertEveryApprovalLeftBothAudits() {
        for (long[] pair : approved) {
            assertThat(approveRequestAuditCount(pair[0])).as("audit yêu cầu %d", pair[0]).isEqualTo(1);
            Map<String, Object> requestRow = fullRequestRow(pair[0]);
            assertThat(requestRow).containsEntry("status", "APPROVED");
            if ("SWAP".equals(requestRow.get("request_type"))) {
                assertThat(swapAuditCount(pair[1])).as("audit đổi ca %d", pair[1]).isEqualTo(1);
                assertThat(onLeaveAuditCount(pair[1])).isZero();
                assertThat(assignmentRow(pair[1])).containsEntry("status", "SCHEDULED")
                        .containsEntry("shift_definition_id", requestRow.get("target_shift_definition_id"));
            } else {
                assertThat(onLeaveAuditCount(pair[1])).as("audit phân ca %d", pair[1]).isEqualTo(1);
                assertThat(swapAuditCount(pair[1])).isZero();
                assertThat(assignmentRow(pair[1])).containsEntry("status", "ON_LEAVE");
            }
        }
    }
}
