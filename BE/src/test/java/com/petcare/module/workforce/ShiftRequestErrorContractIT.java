package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Hợp đồng lỗi của yêu cầu nghỉ / đổi ca (workforce-v1 #9–11; docs/adr/0149 bước 6) qua {@code GlobalExceptionHandler}:
 * mỗi đầu vào sai ra đúng {@code errorCode} / HTTP của nó, envelope luôn đủ sáu trường, <b>không bao giờ 500</b>, và
 * không để lại dấu vết. {@code VALIDATION_FAILED} chỉ cho Bean Validation (thiếu / quá dài {@code reason}, thiếu
 * {@code type} hay {@code shiftAssignmentId}); enum sai, JSON hỏng và sai kiểu là {@code MALFORMED_REQUEST}; vi phạm
 * BR-NS-04 là {@code BUSINESS_RULE_VIOLATION} kết thúc bằng mã rule. Era <b>năm 2066</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestErrorContractIT.TestBeans.class})
class ShiftRequestErrorContractIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2066-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2066, 3, 21);
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
    private long request;
    private String token;
    private String before;
    private final List<ResponseEntity<Map<String, Object>>> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
        request = pendingRequest(assignment, staff);
        token = token(staff);
        before = requestFootprint(assignment, staff);
    }

    @AfterEach
    void tearDown() {
        try {
            assertThat(seen).as("không có lỗi đầu vào nào được phép ra 5xx")
                    .allSatisfy(response -> assertThat(response.getStatusCode().is5xxServerError()).isFalse());
        } finally {
            purgeSubmittedOutbox();
            finish();
        }
    }

    // ---------------------------------------------------------------- VALIDATION_FAILED: chỉ Bean Validation

    @Test
    void aMissingNullBlankOrTooLongReasonIs400ValidationFailedNamingTheField() {
        String tooLong = REASON_PREFIX + "x".repeat(298); // 301 ký tự
        for (String json : List.of(requestBody(assignment, "LEAVE", null, null),
                "{'shiftAssignmentId':" + assignment + ",'type':'LEAVE','reason':null}",
                requestBody(assignment, "LEAVE", null, ""), requestBody(assignment, "LEAVE", null, "   "),
                requestBody(assignment, "LEAVE", null, tooLong))) {
            ResponseEntity<Map<String, Object>> response = post(json);

            assertEnvelope(response, 400, "VALIDATION_FAILED");
            assertThat(message(response)).contains("reason");
        }
        assertUntouched();
    }

    @Test
    void aMissingAssignmentIdOrTypeIs400ValidationFailedAndEveryViolationIsReported() {
        ResponseEntity<Map<String, Object>> noAssignment = post(requestBody(null, "LEAVE", null, REASON));
        ResponseEntity<Map<String, Object>> noType = post(requestBody(assignment, null, null, REASON));
        ResponseEntity<Map<String, Object>> empty = post("{}");

        assertEnvelope(noAssignment, 400, "VALIDATION_FAILED");
        assertThat(message(noAssignment)).contains("shiftAssignmentId");
        assertEnvelope(noType, 400, "VALIDATION_FAILED");
        assertThat(message(noType)).contains("type");
        assertEnvelope(empty, 400, "VALIDATION_FAILED");
        assertThat(message(empty)).contains("shiftAssignmentId").contains("type").contains("reason");
        assertUntouched();
    }

    // ---------------------------------------------------------------- MALFORMED_REQUEST: không phải Bean Validation

    @Test
    void anUnknownTypeBrokenJsonAWrongTypedOrFractionalIdAndAMissingBodyAreMalformed400() {
        for (String json : List.of("{'shiftAssignmentId':" + assignment + ",'type':'HOLIDAY','reason':'" + REASON + "'}",
                "{'shiftAssignmentId':'abc','type':'LEAVE','reason':'" + REASON + "'}",
                "{'shiftAssignmentId':1.5,'type':'LEAVE','reason':'" + REASON + "'}",
                "{'shiftAssignmentId':" + assignment + ",'type':'SWAP','targetShiftDefinitionId':'x','reason':'"
                        + REASON + "'}",
                "[1,2]", "khong phai json", "{'shiftAssignmentId':")) {
            assertEnvelope(post(json), 400, "MALFORMED_REQUEST");
        }
        assertEnvelope(call(HttpMethod.POST, REQUESTS, token), 400, "MALFORMED_REQUEST");
        assertUntouched();
    }

    @Test
    void aWrongTypedQueryParameterOrPathIdIsMalformed400() {
        for (String query : List.of("?status=FOO", "?status=pending", "?page=abc", "?size=1.5")) {
            assertEnvelope(call(HttpMethod.GET, REQUESTS + query, token), 400, "MALFORMED_REQUEST");
        }
        assertEnvelope(call(HttpMethod.POST, REQUESTS + "/abc/withdraw", token), 400, "MALFORMED_REQUEST");
        assertEnvelope(call(HttpMethod.POST, REQUESTS + "/1.5/withdraw", token), 400, "MALFORMED_REQUEST");
        assertUntouched();
    }

    // ---------------------------------------------------------------- BUSINESS_RULE_VIOLATION: mã rule ở cuối message

    @Test
    void everyBrNs04ViolationIsBusinessRuleViolationEndingWithTheRuleCodeAndWritesNothing() {
        long retired = definition(branch, "Ca đã ngừng", "18:00", "22:00", false);
        long closed = data.assignment(branch, staff, afternoon, DAY.plusDays(1), "CANCELLED", "SELF", manager);

        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put(requestBody(closed, "LEAVE", null, REASON), "phân ca đang chờ");
        cases.put(requestBody(assignment, "SWAP", null, REASON), "phải chọn ca đích");
        cases.put(requestBody(assignment, "LEAVE", afternoon, REASON), "không có ca đích");
        // Hai nhánh về ca đích cần phân ca chưa có yêu cầu chờ (kiểm "đã có yêu cầu chờ" đứng trước kiểm ca đích).
        long fresh = data.assignment(branch, staff, morning, DAY.plusDays(2), "SCHEDULED", "SELF", null);
        cases.put(swapBody(fresh, retired), "đã ngừng dùng");
        cases.put(swapBody(fresh, morning), "trùng với ca hiện tại");
        for (Map.Entry<String, String> one : cases.entrySet()) {
            ResponseEntity<Map<String, Object>> response = post(one.getKey());

            assertEnvelope(response, 400, "BUSINESS_RULE_VIOLATION");
            assertThat(message(response)).contains(one.getValue()).endsWith("(BR-NS-04)");
        }
        assertThat(requestCountOf(fresh)).isZero();
        // Đã có yêu cầu chờ (seed ở setUp) là nhánh còn lại.
        ResponseEntity<Map<String, Object>> pending = post(leaveBody(assignment));
        assertEnvelope(pending, 400, "BUSINESS_RULE_VIOLATION");
        assertThat(message(pending)).contains("đã có yêu cầu đang chờ").endsWith("(BR-NS-04)");
        assertUntouched();
    }

    // ---------------------------------------------------------------- 401 / 403 / 404 / 405 / 409: envelope đủ sáu trường

    @Test
    void theOtherStatusesAlsoUseTheFullEnvelopeAndTheirOwnErrorCode() {
        long colleagueAssignment = data.assignment(branch, data.staff("VET", branch), afternoon, DAY, "SCHEDULED",
                "SELF", null);
        long closedRequest = data.leaveRequest(colleagueAssignment, staff, "WITHDRAWN", null);

        assertEnvelope(call(HttpMethod.POST, REQUESTS, null, leaveBody(assignment)), 401, "UNAUTHENTICATED");
        assertEnvelope(call(HttpMethod.POST, REQUESTS, token(manager), leaveBody(assignment)), 403, "ACCESS_DENIED");
        assertEnvelope(post(leaveBody(Long.MAX_VALUE - 1)), 404, "RESOURCE_NOT_FOUND");
        assertEnvelope(call(HttpMethod.POST, withdrawPath(Long.MAX_VALUE - 1), token), 404, "RESOURCE_NOT_FOUND");
        assertEnvelope(call(HttpMethod.POST, withdrawPath(closedRequest), token), 409, "INVALID_STATE_TRANSITION");
        assertEnvelope(call(HttpMethod.PUT, REQUESTS, token, leaveBody(assignment)), 405, "METHOD_NOT_ALLOWED");
        assertThat(fullRequestRow(closedRequest)).containsEntry("status", "WITHDRAWN");
        assertThat(requestFootprint(assignment, staff)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> post(String json) {
        return call(HttpMethod.POST, REQUESTS, token, json);
    }

    /** Envelope lỗi đủ sáu trường, đúng HTTP và {@code errorCode}; ghi lại để {@code tearDown} kiểm không có 5xx. */
    private void assertEnvelope(ResponseEntity<Map<String, Object>> response, int status, String errorCode) {
        seen.add(response);
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body.keySet()).containsAll(ENVELOPE);
        assertThat(body).containsEntry("success", false).containsEntry("statusCode", status)
                .containsEntry("errorCode", errorCode);
        assertThat(String.valueOf(body.get("message"))).isNotBlank();
        assertThat(String.valueOf(body.get("traceId"))).isNotBlank();
    }

    private void assertUntouched() {
        assertThat(requestFootprint(assignment, staff)).isEqualTo(before);
        assertThat(fullRequestRow(request)).containsEntry("status", "PENDING");
    }
}
