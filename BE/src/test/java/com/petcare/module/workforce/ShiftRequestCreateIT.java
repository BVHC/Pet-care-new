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
 * Gửi yêu cầu nghỉ / đổi ca (workforce-v1 #9; docs/adr/0149 bước 6) trên Postgres thật: hình dạng response đủ 15 khóa,
 * hai hiệu ứng cùng một transaction (yêu cầu {@code PENDING} + một dòng outbox cho mỗi quản lý), không audit (BR-NS-10),
 * lý do được strip và dùng thống nhất, payload thông báo khớp mẫu V40, dự phòng SUPER_MANAGER, từng nhánh 400 / 404 của
 * BR-NS-04 (mỗi nhánh không để lại dấu vết) và [CFG] {@code workforce.request_lead_hours} được đọc ở mỗi lần gửi. Era
 * <b>năm 2062</b>; đồng hồ đứng ở 08:00 giờ Việt Nam để chạm đúng biên 24 giờ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestCreateIT.TestBeans.class})
class ShiftRequestCreateIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2062-03-14T01:00:00Z"); // 08:00 giờ Việt Nam
    private static final LocalDate TOMORROW = LocalDate.of(2062, 3, 15);
    private static final LocalDate DAY = LocalDate.of(2062, 3, 21);

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
    private long colleague;
    private long morning;
    private long afternoon;
    private long assignment;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        colleague = data.staff("VET", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        token = token(staff);
    }

    @AfterEach
    void tearDown() {
        try {
            setLeadHours(24);
        } finally {
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void aLeaveReturns201WithTheFifteenContractKeysAndStoresAPendingRowWithoutAnyAudit() {
        int audits = auditCountOfActor(staff);

        ResponseEntity<Map<String, Object>> response = post(requestBody(assignment, "LEAVE", null,
                "  " + REASON + "  "));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(body).containsEntry("shiftAssignmentId", (int) assignment).containsEntry("requesterId", (int) staff)
                .containsEntry("requesterName", nameOf(staff)).containsEntry("workDate", DAY.toString())
                .containsEntry("type", "LEAVE").containsEntry("targetShiftDefinitionId", null)
                .containsEntry("targetShiftName", null).containsEntry("reason", REASON)
                .containsEntry("status", "PENDING").containsEntry("decidedBy", null)
                .containsEntry("decisionNote", null).containsEntry("closedAt", null);
        assertThat((String) body.get("currentShiftName")).startsWith("Ca sáng");
        assertThat(Instant.parse((String) body.get("createdAt"))).isEqualTo(NOW);

        Map<String, Object> row = fullRequestRow(onlyRequestIdOf(assignment));
        assertThat(row).containsEntry("requester_id", staff).containsEntry("request_type", "LEAVE")
                .containsEntry("target_shift_definition_id", null).containsEntry("reason", REASON)
                .containsEntry("status", "PENDING").containsEntry("decided_by", null)
                .containsEntry("decision_note", null).containsEntry("closed_at", null);
        assertThat(auditCountOfActor(staff)).as("BR-NS-10: gửi yêu cầu không ghi audit").isEqualTo(audits);
        assertThat(assignmentRow(assignment)).as("phân ca giữ nguyên").containsEntry("status", "SCHEDULED");
    }

    @Test
    void aSwapKeepsTheTargetAndReportsItsCurrentName() {
        ResponseEntity<Map<String, Object>> response = post(swapBody(assignment, afternoon));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> body = dataOf(response);
        assertThat(body).containsEntry("type", "SWAP").containsEntry("targetShiftDefinitionId", (int) afternoon);
        assertThat((String) body.get("targetShiftName")).startsWith("Ca chiều");
        assertThat(fullRequestRow(onlyRequestIdOf(assignment))).containsEntry("target_shift_definition_id", afternoon);
        assertThat(payloadField(manager, "loai_yeu_cau")).isEqualTo("đổi");
    }

    @Test
    void theReasonIsStrippedOnceAndUsedByTheResponseTheRowAndTheNoticeAlike() {
        ResponseEntity<Map<String, Object>> response = post(requestBody(assignment, "LEAVE", null,
                "   " + REASON + "   "));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(dataOf(response)).containsEntry("reason", REASON);
        assertThat(fullRequestRow(onlyRequestIdOf(assignment))).containsEntry("reason", REASON);
        assertThat(payloadField(manager, "ly_do")).isEqualTo(REASON);
    }

    @Test
    void theReasonIsStoredAtTheFullColumnLengthWithVietnameseCharacters() {
        String longest = REASON_PREFIX + "ệ".repeat(300 - REASON_PREFIX.length());

        ResponseEntity<Map<String, Object>> response = post(requestBody(assignment, "LEAVE", null, longest));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT char_length(reason) FROM shift_requests WHERE assignment_id = ?",
                Integer.class, assignment)).isEqualTo(300);
        assertThat(payloadField(manager, "ly_do")).isEqualTo(longest);
    }

    @Test
    void extraBodyFieldsCannotChangeTheRequesterTheStatusOrTheDecider() {
        ResponseEntity<Map<String, Object>> response = post("{'shiftAssignmentId':" + assignment
                + ",'type':'LEAVE','reason':'" + REASON + "','requesterId':" + colleague
                + ",'status':'APPROVED','decidedBy':" + manager + ",'closedAt':'2062-03-14T00:00:00Z'}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(fullRequestRow(onlyRequestIdOf(assignment))).containsEntry("requester_id", staff)
                .containsEntry("status", "PENDING").containsEntry("decided_by", null)
                .containsEntry("closed_at", null);
    }

    // ---------------------------------------------------------------- thông báo

    @Test
    void everyActiveBranchManagerGetsOneInAppNoticeThatFitsTheV40TemplateAndTheStaffGetsNone() {
        long secondManager = data.staff("BRANCH_MANAGER", branch);

        assertThat(post(leaveBody(assignment)).getStatusCode().value()).isEqualTo(201);

        for (long recipient : new long[] {manager, secondManager}) {
            List<Map<String, Object>> notices = submittedOutbox(recipient);
            assertThat(notices).hasSize(1);
            assertThat(notices.get(0)).containsEntry("channel", "IN_APP").containsEntry("template_code", TEMPLATE)
                    .containsEntry("recipient_email", null);
            assertThat(payloadField(recipient, "ten_nhan_vien")).isEqualTo(nameOf(staff));
            assertThat(payloadField(recipient, "loai_yeu_cau")).isEqualTo("nghỉ");
            assertThat(payloadField(recipient, "ten_ca")).startsWith("Ca sáng");
            assertThat(payloadField(recipient, "ngay_lam")).isEqualTo("21/03/2062");
            assertThat(payloadField(recipient, "chi_nhanh")).isEqualTo(branchName(branch));
            assertThat(payloadField(recipient, "ly_do")).isEqualTo(REASON);
            assertTemplateFit(recipient);
        }
        assertThat(submittedOutbox(staff)).as("người gửi không nhận gì").isEmpty();
        assertThat(submittedOutboxCount()).isEqualTo(2);
    }

    @Test
    void whenTheBranchHasNoActiveManagerTheSuperManagerIsNotifiedInstead() {
        long lonelyBranch = data.branch();
        long lonelyStaff = data.staff("CARETAKER", lonelyBranch);
        long superManager = data.staff("SUPER_MANAGER", null);
        long lonelyMorning = definition(lonelyBranch, "Ca sáng", "08:00", "12:00", true);
        long lonelyAssignment = data.assignment(lonelyBranch, lonelyStaff, lonelyMorning, DAY, "SCHEDULED", "SELF",
                null);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, REQUESTS, token(lonelyStaff),
                leaveBody(lonelyAssignment));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(submittedOutbox(superManager)).hasSize(1);
        assertThat(payloadField(superManager, "chi_nhanh")).isEqualTo(branchName(lonelyBranch));
        assertThat(submittedOutbox(manager)).as("quản lý chi nhánh khác không nhận").isEmpty();
    }

    // ---------------------------------------------------------------- BR-NS-04: 400, mỗi nhánh không để lại dấu vết

    @Test
    void anAssignmentThatIsNotScheduledIs400BecauseItIsACreationCondition() {
        for (String status : List.of("CANCELLED", "ON_LEAVE", "ABSENT")) {
            long closed = data.assignment(branch, staff, afternoon, DAY.plusDays(1 + status.length()), status, "SELF",
                    manager);

            assertRejected(leaveBody(closed), closed, "phân ca đang chờ");
        }
    }

    @Test
    void exactlyTheConfiguredHoursAheadIsAcceptedAndOneMinuteLessIsNot() {
        long exactly = data.assignment(branch, staff, morning, TOMORROW, "SCHEDULED", "SELF", null); // 08:00 mai
        long late = definition(branch, "Ca 07:59", "07:59", "12:00", true);
        long tooSoon = data.assignment(branch, staff, late, TOMORROW, "SCHEDULED", "SELF", null);

        assertRejected(leaveBody(tooSoon), tooSoon, "ít nhất 24 giờ");
        assertThat(post(leaveBody(exactly)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void theLeadHoursConfigurationIsReadOnEveryCallSoAChangeAppliesFromTheNextSubmission() {
        long tomorrow = data.assignment(branch, staff, morning, TOMORROW, "SCHEDULED", "SELF", null);

        setLeadHours(48);
        assertRejected(leaveBody(tomorrow), tomorrow, "ít nhất 48 giờ");
        setLeadHours(24);

        assertThat(post(leaveBody(tomorrow)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void anAssignmentThatAlreadyHasAPendingRequestIs400EvenWhenTheFirstOneCameFromThisEndpoint() {
        assertThat(post(leaveBody(assignment)).getStatusCode().value()).isEqualTo(201);

        assertRejected(leaveBody(assignment), assignment, "đã có yêu cầu đang chờ");
        assertThat(requestCountOf(assignment)).isEqualTo(1);
    }

    @Test
    void aClosedRequestDoesNotBlockANewOne() {
        data.leaveRequest(assignment, staff, "REJECTED", null);

        assertThat(post(leaveBody(assignment)).getStatusCode().value()).isEqualTo(201);
        assertThat(requestCountOf(assignment)).isEqualTo(2);
    }

    @Test
    void aSwapNeedsATargetAndALeaveMustNotHaveOne() {
        assertRejected(requestBody(assignment, "SWAP", null, REASON), assignment, "phải chọn ca đích");
        assertRejected(requestBody(assignment, "LEAVE", afternoon, REASON), assignment, "không có ca đích");
    }

    @Test
    void aTargetThatIsInactiveOrTheSameAsTheCurrentShiftIs400() {
        long retired = definition(branch, "Ca đã ngừng", "18:00", "22:00", false);

        assertRejected(swapBody(assignment, retired), assignment, "đã ngừng dùng");
        assertRejected(swapBody(assignment, morning), assignment, "trùng với ca hiện tại");
    }

    @Test
    void aTargetOverlappingAnotherScheduledAssignmentOfTheDayIs400AndNamesItButAdjacentAndOwnShiftAreFine() {
        long evening = definition(branch, "Ca tối", "18:00", "22:00", true);
        long eveningAssignment = data.assignment(branch, staff, evening, DAY, "SCHEDULED", "SELF", null);
        long wideTarget = definition(branch, "Ca dài", "11:00", "19:00", true);
        long adjacentTarget = definition(branch, "Ca liền kề", "12:00", "16:00", true);
        long overlapsOnlyItself = definition(branch, "Ca giao ca sáng", "10:00", "13:00", true);

        // Ca tối đổi sang 11:00–19:00: giao ca sáng (08–12) của người đó → chặn và nêu tên ca đang chồng.
        ResponseEntity<Map<String, Object>> overlap = call(HttpMethod.POST, REQUESTS, token,
                swapBody(eveningAssignment, wideTarget));
        assertBusinessRule(overlap, "BR-NS-04");
        assertThat(message(overlap)).contains("Ca sáng");
        assertThat(requestCountOf(eveningAssignment)).isZero();

        // Liền kề (kết thúc 12:00 = bắt đầu 12:00) không chồng.
        assertThat(post(swapBody(eveningAssignment, adjacentTarget)).getStatusCode().value()).isEqualTo(201);
        // Ca sáng đổi sang 10:00–13:00 giao chính ca sáng đang đổi: không tính, và không giao ca tối.
        assertThat(post(swapBody(assignment, overlapsOnlyItself)).getStatusCode().value()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- 404, không lộ phân ca của người khác

    @Test
    void aColleaguesAssignmentAnUnknownOneAndAForeignOrUnknownTargetAreAll404AndLeaveNoTrace() {
        long colleagueAssignment = data.assignment(branch, colleague, afternoon, DAY, "SCHEDULED", "SELF", null);
        long otherBranch = data.branch();
        long foreignTarget = definition(otherBranch, "Ca chi nhánh khác", "13:00", "17:00", true);
        String before = requestFootprint(assignment, staff);
        String colleagueBefore = requestFootprint(colleagueAssignment, colleague);

        for (String json : List.of(leaveBody(colleagueAssignment), leaveBody(Long.MAX_VALUE - 1),
                swapBody(assignment, foreignTarget), swapBody(assignment, Long.MAX_VALUE - 1))) {
            ResponseEntity<Map<String, Object>> response = post(json);

            assertThat(response.getStatusCode().value()).as(json).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(requestFootprint(assignment, staff)).isEqualTo(before);
        assertThat(requestFootprint(colleagueAssignment, colleague)).isEqualTo(colleagueBefore);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> post(String json) {
        return call(HttpMethod.POST, REQUESTS, token, json);
    }

    /** 400 {@code BR-NS-04} có đoạn message mong đợi và không để lại dấu vết nào. */
    private void assertRejected(String json, long assignmentId, String fragment) {
        String before = requestFootprint(assignmentId, staff);

        ResponseEntity<Map<String, Object>> response = post(json);

        assertBusinessRule(response, "BR-NS-04");
        assertThat(message(response)).contains(fragment);
        assertThat(requestFootprint(assignmentId, staff)).isEqualTo(before);
    }

    private void setLeadHours(int hours) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'workforce.request_lead_hours'",
                String.valueOf(hours));
        configService.reload();
    }

    private String nameOf(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class,
                accountId);
    }

    private String branchName(long branchId) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branchId);
    }
}
