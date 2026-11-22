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
 * Phân ca của chi nhánh (workforce-v1 #4–5; docs/adr/0138 bước 3) trên Postgres thật: hình dạng response theo hợp đồng,
 * từng nhánh 400 / 404 / validation, định nghĩa chồng giờ (chạm biên hợp lệ), giao với guard của {@code PATCH} ca, và
 * danh sách (sắp, lọc, phân trang, trần 62 ngày, tên nhân viên đã nghỉ, yêu cầu chờ). Era <b>năm 2050</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentIT.TestBeans.class})
class ShiftAssignmentIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2050-03-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2050, 3, 14);
    private static final String DAY = "2050-03-21";
    private static final List<String> CONTRACT_KEYS = List.of("shiftAssignmentId", "branchId", "staffId", "staffName",
            "shiftDefinitionId", "shiftName", "startTime", "endTime", "workDate", "status", "source", "reason",
            "pendingRequestId");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branch;
    private long otherBranch;
    private long manager;
    private long staff;
    private long morning;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        otherBranch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- tạo: hợp đồng và ghi

    @Test
    void createReturns201WithExactlyTheContractFieldsAndWritesOneManagerRowAndOneAudit() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> created = dataOf(response);
        assertThat(created.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
        assertThat(created).containsEntry("branchId", (int) branch).containsEntry("staffId", (int) staff)
                .containsEntry("shiftDefinitionId", (int) morning).containsEntry("startTime", "08:00")
                .containsEntry("endTime", "12:00").containsEntry("workDate", DAY)
                .containsEntry("status", "SCHEDULED").containsEntry("source", "MANAGER")
                .containsEntry("reason", null).containsEntry("pendingRequestId", null);
        assertThat((String) created.get("staffName")).isNotBlank();
        assertThat((String) created.get("shiftName")).startsWith("Ca sáng");
        long id = idOf(created, "shiftAssignmentId");
        assertThat(assignmentRow(id)).containsEntry("branch_id", branch).containsEntry("staff_id", staff)
                .containsEntry("work_date", DAY).containsEntry("status", "SCHEDULED")
                .containsEntry("source", "MANAGER");
        assertThat(assignmentRow(id).get("status_changed_at")).isNull();
        assertThat(assignmentAuditCountOfStaff(staff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT after_data->>'source' FROM audit_logs
                WHERE entity_type = 'shift_assignments' AND entity_id = ?""", String.class, id)).isEqualTo("MANAGER");
    }

    @Test
    void sendingTheSameBodyTwiceCreatesOnceAndTheSecondIsAnOverlap() {
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)).getStatusCode().value())
                .isEqualTo(201);

        ResponseEntity<Map<String, Object>> second = call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY));

        assertBusinessRule(second, "BR-NS-02");
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(1);
        assertThat(assignmentAuditCountOfStaff(staff)).isEqualTo(1);
    }

    @Test
    void todayIsAllowed() {
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, TODAY.toString())).getStatusCode()
                .value()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- tạo: chồng giờ

    @Test
    void overlappingHoursAreRejectedAndTheMessageNamesTheClashingShift() {
        scheduled(branch, staff, morning, LocalDate.of(2050, 3, 21));
        long midday = definition(branch, "Ca trưa", "10:00", "14:00", true);
        long inside = definition(branch, "Ca ngắn", "09:00", "10:00", true);
        long covering = definition(branch, "Ca dài", "07:00", "18:00", true);

        for (long other : new long[] {midday, inside, covering}) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token,
                    body(staff, other, DAY));
            assertBusinessRule(response, "BR-NS-02");
            assertThat(message(response)).contains((String) row(morning).get("name")).contains("08:00–12:00");
        }
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(1);
    }

    @Test
    void shiftsThatOnlyTouchAtABoundaryAreNotOverlapping() {
        scheduled(branch, staff, morning, LocalDate.of(2050, 3, 21));
        long afternoon = definition(branch, "Ca chiều", "12:00", "16:00", true);
        long early = definition(branch, "Ca sớm", "06:00", "08:00", true);

        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, afternoon, DAY)).getStatusCode().value())
                .isEqualTo(201);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, early, DAY)).getStatusCode().value())
                .isEqualTo(201);
        assertThat(assignmentCountOfStaff(staff)).isEqualTo(3);
    }

    @Test
    void theSameShiftOnAnotherDayAndAClosedAssignmentDoNotBlock() {
        scheduled(branch, staff, morning, LocalDate.of(2050, 3, 21));
        data.assignment(branch, staff, morning, LocalDate.of(2050, 3, 22), "CANCELLED", "MANAGER", manager);
        data.assignment(branch, staff, morning, LocalDate.of(2050, 3, 23), "ON_LEAVE", "MANAGER", manager);

        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, "2050-03-22")).getStatusCode().value())
                .isEqualTo(201);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, "2050-03-23")).getStatusCode().value())
                .isEqualTo(201);
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, "2050-03-24")).getStatusCode().value())
                .isEqualTo(201);
    }

    // ---------------------------------------------------------------- tạo: các nhánh 400 / 404

    @Test
    void aPastDateAnInactiveShiftAndAnIneligibleStaffMemberAreEach400BrNs02AndWriteNothing() {
        long inactive = definition(branch, "Ca ngừng", "13:00", "17:00", false);
        long disabled = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        long locked = data.staff("CARETAKER", branch);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", locked);

        assertBusinessRule(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, "2050-03-13")), "BR-NS-02");
        assertBusinessRule(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, inactive, DAY)), "BR-NS-02");
        assertBusinessRule(call(HttpMethod.POST, ASSIGNMENTS, token, body(disabled, morning, DAY)), "BR-NS-02");
        assertBusinessRule(call(HttpMethod.POST, ASSIGNMENTS, token, body(locked, morning, DAY)), "BR-NS-02");

        assertThat(assignmentCount(branch)).isZero();
        assertThat(assignmentAuditCountOfStaff(staff)).isZero();
    }

    @Test
    void anUnknownStaffMemberOrShiftIs404() {
        ResponseEntity<Map<String, Object>> noStaff = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(Long.MAX_VALUE - 1, morning, DAY));
        ResponseEntity<Map<String, Object>> noShift = call(HttpMethod.POST, ASSIGNMENTS, token,
                body(staff, Long.MAX_VALUE - 1, DAY));

        assertThat(noStaff.getStatusCode().value()).isEqualTo(404);
        assertThat(noShift.getStatusCode().value()).isEqualTo(404);
        assertThat(assignmentCount(branch)).isZero();
    }

    // ---------------------------------------------------------------- tạo: validation đầu vào

    @Test
    void missingFieldsAreValidationFailedWithVietnameseMessages() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token, "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("VALIDATION_FAILED");
        assertThat(message(response)).contains("Vui lòng chọn nhân viên").contains("Vui lòng chọn ca làm việc")
                .contains("Vui lòng chọn ngày làm");
    }

    @Test
    void malformedTypedValuesAreMalformedRequestNot500() {
        for (String json : List.of(
                "{",
                "{'staffId':" + staff + ",'shiftDefinitionId':" + morning + ",'workDate':'2050-13-45'}",
                "{'staffId':" + staff + ",'shiftDefinitionId':" + morning + ",'workDate':'21/03/2050'}",
                "{'staffId':1.5,'shiftDefinitionId':" + morning + ",'workDate':'" + DAY + "'}",
                "{'staffId':'abc','shiftDefinitionId':" + morning + ",'workDate':'" + DAY + "'}")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, ASSIGNMENTS, token, json);

            assertThat(response.getStatusCode().value()).as(json).isEqualTo(400);
            assertThat(errorCode(response)).as(json).isEqualTo("MALFORMED_REQUEST");
        }
        assertThat(assignmentCount(branch)).isZero();
    }

    // ---------------------------------------------------------------- giao với guard của PATCH ca (BR-NS-01)

    @Test
    void onceAnAssignmentExistsTheShiftCannotBeRetimedOrDeactivated() {
        assertThat(call(HttpMethod.POST, ASSIGNMENTS, token, body(staff, morning, DAY)).getStatusCode().value())
                .isEqualTo(201);

        ResponseEntity<Map<String, Object>> retime = call(HttpMethod.PATCH, PATH + "/" + morning, token,
                "{'startTime':'07:00'}");
        ResponseEntity<Map<String, Object>> deactivate = call(HttpMethod.PATCH, PATH + "/" + morning, token,
                "{'active':false}");
        ResponseEntity<Map<String, Object>> rename = call(HttpMethod.PATCH, PATH + "/" + morning, token,
                "{'name':'Ca sáng đổi tên'}");

        assertBusinessRule(retime, "BR-NS-01");
        assertBusinessRule(deactivate, "BR-NS-01");
        assertThat(rename.getStatusCode().value()).as("đổi tên vẫn được").isEqualTo(200);
    }

    // ---------------------------------------------------------------- danh sách

    @Test
    void listIsSortedByDateThenShiftStartThenIdAndEveryItemHasTheContractFields() {
        long afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        long other = data.staff("VET", branch);
        long r1 = scheduled(branch, staff, afternoon, LocalDate.of(2050, 3, 22));
        long r2 = scheduled(branch, other, afternoon, LocalDate.of(2050, 3, 21));
        long r3 = scheduled(branch, staff, morning, LocalDate.of(2050, 3, 21));
        long r4 = data.assignment(branch, other, morning, LocalDate.of(2050, 3, 21), "CANCELLED", "SELF", manager);
        long foreign = scheduled(otherBranch, data.staff("VET", otherBranch),
                definition(otherBranch, "Ca ngoài", "08:00", "12:00", true), LocalDate.of(2050, 3, 21));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, listPath("2050-03-21", "2050-03-22", ""), token);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> content = contentOf(dataOf(response));
        assertThat(content).extracting(m -> idOf(m, "shiftAssignmentId")).containsExactly(r3, r4, r2, r1)
                .doesNotContain(foreign);
        assertThat(content).allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS));
        assertThat(content.get(1)).containsEntry("status", "CANCELLED").containsEntry("source", "SELF")
                .containsEntry("reason", "Lý do");
        assertThat(dataOf(response)).containsEntry("totalElements", 4);
    }

    @Test
    void listFiltersByStaffStatusAndDateRange() {
        long other = data.staff("VET", branch);
        long r1 = scheduled(branch, staff, morning, LocalDate.of(2050, 3, 22));
        long r2 = scheduled(branch, other, morning, LocalDate.of(2050, 3, 21));
        long r3 = data.assignment(branch, other, definition(branch, "Ca chiều", "13:00", "17:00", true),
                LocalDate.of(2050, 3, 21), "CANCELLED", "MANAGER", manager);

        assertThat(ids(call(HttpMethod.GET, listPath("2050-03-21", "2050-03-22", "&staffId=" + other), token)))
                .containsExactly(r2, r3);
        assertThat(ids(call(HttpMethod.GET, listPath("2050-03-21", "2050-03-22", "&status=CANCELLED"), token)))
                .containsExactly(r3);
        assertThat(ids(call(HttpMethod.GET, listPath("2050-03-22", "2050-03-22", ""), token))).containsExactly(r1);
        assertThat(ids(call(HttpMethod.GET, listPath("2050-03-23", "2050-03-25", ""), token))).isEmpty();
    }

    @Test
    void listPagesAndClampsPageAndSize() {
        for (int day = 21; day <= 25; day++) {
            scheduled(branch, staff, morning, LocalDate.of(2050, 3, day));
        }

        Map<String, Object> second = dataOf(call(HttpMethod.GET, listPath("2050-03-21", "2050-03-27", "&page=1&size=2"),
                token));
        assertThat(contentOf(second)).hasSize(2);
        assertThat(second).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 5)
                .containsEntry("totalPages", 3);
        assertThat(dataOf(call(HttpMethod.GET, listPath("2050-03-21", "2050-03-27", "&size=500"), token)))
                .containsEntry("size", 100);
        assertThat(dataOf(call(HttpMethod.GET, listPath("2050-03-21", "2050-03-27", "&size=0&page=-4"), token)))
                .containsEntry("size", 1).containsEntry("page", 0);
    }

    @Test
    void listRangeIsAtMost62DaysInclusiveAndInvertedRangesAreRejected() {
        assertThat(call(HttpMethod.GET, listPath("2050-03-01", "2050-05-01", ""), token).getStatusCode().value())
                .as("đúng 62 ngày tính cả hai đầu").isEqualTo(200);
        assertBusinessRule(call(HttpMethod.GET, listPath("2050-03-01", "2050-05-02", ""), token), "BR-NS-03");
        assertBusinessRule(call(HttpMethod.GET, listPath("2050-03-10", "2050-03-09", ""), token), "BR-NS-03");
    }

    @Test
    void listWithMissingOrMalformedParametersIsMalformedRequest() {
        for (String path : List.of(ASSIGNMENTS, ASSIGNMENTS + "?to=2050-03-28", ASSIGNMENTS + "?from=2050-03-14",
                ASSIGNMENTS + "?from=abc&to=2050-03-28", listPath("2050-03-14", "2050-03-28", "&status=BOGUS"),
                listPath("2050-03-14", "2050-03-28", "&staffId=xyz"))) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, path, token);

            assertThat(response.getStatusCode().value()).as(path).isEqualTo(400);
            assertThat(errorCode(response)).as(path).isEqualTo("MALFORMED_REQUEST");
        }
    }

    @Test
    void listShowsTheNameOfAStaffMemberWhoLeftAndOnlyPendingRequests() {
        long leaver = data.staff("VET", branch);
        long withPending = scheduled(branch, leaver, morning, LocalDate.of(2050, 3, 21));
        long withApproved = scheduled(branch, staff, morning, LocalDate.of(2050, 3, 22));
        long without = scheduled(branch, staff, morning, LocalDate.of(2050, 3, 23));
        long pendingId = data.leaveRequest(withPending, leaver, "PENDING", null);
        data.leaveRequest(withApproved, staff, "APPROVED", manager);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", leaver);

        List<Map<String, Object>> content = contentOf(dataOf(call(HttpMethod.GET,
                listPath("2050-03-21", "2050-03-23", ""), token)));

        assertThat(content).extracting(m -> idOf(m, "shiftAssignmentId"))
                .containsExactly(withPending, withApproved, without);
        assertThat(content.get(0)).containsEntry("pendingRequestId", (int) pendingId);
        assertThat((String) content.get(0).get("staffName")).as("người đã nghỉ vẫn có tên").isNotBlank();
        assertThat(content.get(1)).containsEntry("pendingRequestId", null);
        assertThat(content.get(2)).containsEntry("pendingRequestId", null);
    }

    private static String listPath(String from, String to, String extra) {
        return ASSIGNMENTS + "?from=" + from + "&to=" + to + extra;
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return contentOf(dataOf(response)).stream().map(m -> idOf(m, "shiftAssignmentId")).toList();
    }
}
