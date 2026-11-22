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
 * Rút yêu cầu (workforce-v1 #11; docs/adr/0138 bước 6) trên Postgres thật: chỉ yêu cầu → {@code WITHDRAWN} (Yêu cầu#2)
 * với {@code closed_at} từ đồng hồ và {@code decided_by} rỗng; phân ca giữ nguyên; không audit, không thông báo; mọi
 * trạng thái đã đóng (kể cả bị hệ thống đóng khi quản lý hủy phân ca) là 409; yêu cầu của đồng nghiệp hay không tồn tại
 * là 404 và không để lại dấu vết; rút xong gửi lại được. Era <b>năm 2063</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestWithdrawIT.TestBeans.class})
class ShiftRequestWithdrawIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2063-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2063, 3, 21);

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
        purgeSubmittedOutbox();
        purgeCancelOutbox(staff);
        finish();
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void withdrawReturns200WithTheFifteenKeysClosesOnlyTheRequestAndWritesNothingElse() {
        long request = pendingRequest(assignment, staff);
        int audits = auditCountOfActor(staff);
        int outboxRows = outboxRowsOf(staff, manager);

        ResponseEntity<Map<String, Object>> response = withdraw(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = dataOf(response);
        assertThat(body.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(body).containsEntry("shiftRequestId", (int) request).containsEntry("status", "WITHDRAWN")
                .containsEntry("decidedBy", null).containsEntry("decisionNote", null)
                .containsEntry("requesterId", (int) staff).containsEntry("type", "LEAVE");
        assertThat(Instant.parse((String) body.get("closedAt"))).isEqualTo(NOW);

        assertThat(fullRequestRow(request)).containsEntry("status", "WITHDRAWN").containsEntry("decided_by", null)
                .containsEntry("decision_note", null);
        assertThat(fullRequestRow(request).get("closed_at")).isNotNull();
        assertThat(assignmentRow(assignment)).as("phân ca giữ nguyên").containsEntry("status", "SCHEDULED")
                .containsEntry("shift_definition_id", morning);
        assertThat(auditCountOfActor(staff)).as("BR-NS-10: rút yêu cầu không ghi audit").isEqualTo(audits);
        assertThat(outboxRowsOf(staff, manager)).as("rút không thông báo (BR-NS-08)").isEqualTo(outboxRows);
    }

    @Test
    void aSwapRequestCanBeWithdrawnAndStillReportsItsTarget() {
        long request = data.swapRequest(assignment, staff, afternoon, "PENDING", null);

        ResponseEntity<Map<String, Object>> response = withdraw(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dataOf(response)).containsEntry("type", "SWAP").containsEntry("status", "WITHDRAWN")
                .containsEntry("targetShiftDefinitionId", (int) afternoon);
        assertThat((String) dataOf(response).get("targetShiftName")).startsWith("Ca chiều");
    }

    @Test
    void afterWithdrawingTheStaffMemberCanSubmitAgainBecauseOnlyPendingRequestsAreUnique() {
        assertThat(post(leaveBody(assignment)).getStatusCode().value()).isEqualTo(201);
        long first = onlyRequestIdOf(assignment);
        assertThat(withdraw(first).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> again = post(leaveBody(assignment));

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(requestCountOf(assignment)).isEqualTo(2);
        assertThat(fullRequestRow(first)).containsEntry("status", "WITHDRAWN");
    }

    // ---------------------------------------------------------------- 409: không còn PENDING

    @Test
    void withdrawingTwiceIs409AndTheSecondCallLeavesNoTrace() {
        long request = pendingRequest(assignment, staff);
        assertThat(withdraw(request).getStatusCode().value()).isEqualTo(200);
        String after = requestFootprint(assignment, staff);

        ResponseEntity<Map<String, Object>> second = withdraw(request);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(second)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(requestFootprint(assignment, staff)).isEqualTo(after);
    }

    @Test
    void everyClosedStatusIs409AndLeavesTheRequestAsItWas() {
        for (String status : List.of("APPROVED", "REJECTED")) {
            long request = data.leaveRequest(assignment, staff, status, manager);
            String before = requestFootprint(assignment, staff);

            ResponseEntity<Map<String, Object>> response = withdraw(request);

            assertThat(response.getStatusCode().value()).as(status).isEqualTo(409);
            assertThat(errorCode(response)).isEqualTo("INVALID_STATE_TRANSITION");
            assertThat(requestFootprint(assignment, staff)).isEqualTo(before);
            assertThat(fullRequestRow(request)).containsEntry("status", status);
        }
    }

    @Test
    void aRequestTheSystemClosedWhenTheManagerCancelledTheAssignmentIs409NotAMissingRow() {
        long request = pendingRequest(assignment, staff);
        ResponseEntity<Map<String, Object>> cancel = call(HttpMethod.POST, cancelPath(assignment), token(manager),
                reasonBody("Quản lý hủy"));
        assertThat(cancel.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map<String, Object>> response = withdraw(request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(fullRequestRow(request)).containsEntry("status", "REJECTED").containsEntry("decided_by", null);
    }

    // ---------------------------------------------------------------- 404 và đầu vào sai

    @Test
    void aColleaguesRequestAndAnUnknownRequestAreBoth404AndLeaveNoTrace() {
        long colleagueAssignment = data.assignment(branch, colleague, afternoon, DAY, "SCHEDULED", "SELF", null);
        long colleagueRequest = pendingRequest(colleagueAssignment, colleague);
        String before = requestFootprint(colleagueAssignment, colleague);

        for (long id : new long[] {colleagueRequest, Long.MAX_VALUE - 1}) {
            ResponseEntity<Map<String, Object>> response = withdraw(id);

            assertThat(response.getStatusCode().value()).as("id=%s", id).isEqualTo(404);
            assertThat(errorCode(response)).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(requestFootprint(colleagueAssignment, colleague)).isEqualTo(before);
        assertThat(fullRequestRow(colleagueRequest)).containsEntry("status", "PENDING");
    }

    @Test
    void aNonNumericIdIsMalformed400() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, REQUESTS + "/abc/withdraw", token);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> withdraw(long requestId) {
        return call(HttpMethod.POST, withdrawPath(requestId), token);
    }

    private ResponseEntity<Map<String, Object>> post(String json) {
        return call(HttpMethod.POST, REQUESTS, token, json);
    }

    private int outboxRowsOf(long... accountIds) {
        int total = 0;
        for (long id : accountIds) {
            total += jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE recipient_account_id = ?",
                    Integer.class, id);
        }
        return total;
    }
}
