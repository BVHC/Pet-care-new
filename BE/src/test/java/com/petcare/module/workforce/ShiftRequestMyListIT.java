package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Yêu cầu của tôi (workforce-v1 #10; docs/adr/0149 bước 6) trên Postgres thật: chỉ trả yêu cầu của chính người gọi,
 * mới nhất trước (trùng thời điểm thì id lớn trước), lọc {@code status}, phân trang và kẹp {@code page} / {@code size},
 * đủ 15 khóa kể cả khóa null, tên ca ghép đúng, gồm cả yêu cầu của phân ca đã hủy (lịch sử). Era <b>năm 2064</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestMyListIT.TestBeans.class})
class ShiftRequestMyListIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2064-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2064, 3, 21);
    private static final OffsetDateTime T0 = OffsetDateTime.of(2064, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);

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
    private String token;
    /** Yêu cầu của {@code staff}, theo thứ tự thời gian tăng dần (chỉ số 0 cũ nhất). */
    private long[] mine;
    private long theirs;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        colleague = data.staff("VET", branch);
        morning = definition(branch, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branch, "Ca chiều", "13:00", "17:00", true);
        token = token(staff);

        mine = new long[5];
        mine[0] = at(data.leaveRequest(assignmentOn(1, "SCHEDULED"), staff, "APPROVED", manager), 0);
        mine[1] = at(data.leaveRequest(assignmentOn(2, "SCHEDULED"), staff, "REJECTED", manager), 1);
        mine[2] = at(data.leaveRequest(assignmentOn(3, "SCHEDULED"), staff, "WITHDRAWN", manager), 2);
        mine[3] = at(data.swapRequest(assignmentOn(4, "SCHEDULED"), staff, afternoon, "PENDING", null), 3);
        // Yêu cầu của một phân ca đã bị hủy vẫn là lịch sử của người gửi.
        mine[4] = at(data.leaveRequest(assignmentOn(5, "CANCELLED"), staff, "REJECTED", manager), 4);
        long colleagueAssignment = data.assignment(branch, colleague, afternoon, DAY, "SCHEDULED", "SELF", null);
        theirs = at(data.leaveRequest(colleagueAssignment, colleague, "PENDING", null), 10);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- phạm vi và thứ tự

    @Test
    void itListsOnlyTheCallersRequestsNewestFirstWithTheFifteenKeysEvenForNullFields() {
        ResponseEntity<Map<String, Object>> response = list("");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> page = dataOf(response);
        assertThat(contentOf(page)).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(mine[4], mine[3], mine[2], mine[1], mine[0]);
        assertThat(contentOf(page)).allSatisfy(item -> {
            assertThat(item.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
            assertThat(idOf(item, "requesterId")).isEqualTo(staff);
        });
        assertThat(contentOf(page)).extracting(item -> idOf(item, "shiftRequestId")).doesNotContain(theirs);
        assertThat(page).containsEntry("totalElements", 5).containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalPages", 1);
    }

    @Test
    void theColleagueSeesOnlyTheirOwnRequest() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, REQUESTS, token(colleague));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(response))).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(theirs);
    }

    @Test
    void aSwapShowsItsTargetNameAndALeaveHasNullTargetsAndTheNamesAreTheCurrentOnes() {
        Map<String, Object> swap = contentOf(dataOf(list(""))).get(1);
        Map<String, Object> leave = contentOf(dataOf(list(""))).get(0);

        assertThat(idOf(swap, "shiftRequestId")).isEqualTo(mine[3]);
        assertThat(swap).containsEntry("type", "SWAP").containsEntry("targetShiftDefinitionId", (int) afternoon)
                .containsEntry("closedAt", null).containsEntry("decidedBy", null);
        assertThat((String) swap.get("targetShiftName")).startsWith("Ca chiều");
        assertThat((String) swap.get("currentShiftName")).startsWith("Ca sáng");
        assertThat(leave).containsEntry("type", "LEAVE").containsEntry("targetShiftDefinitionId", null)
                .containsEntry("targetShiftName", null).containsEntry("status", "REJECTED");
        assertThat(leave.get("closedAt")).isNotNull();
    }

    @Test
    void requestsCreatedAtTheSameInstantAreOrderedByIdDescending() {
        long a = data.leaveRequest(assignmentOn(6, "SCHEDULED"), staff, "REJECTED", manager);
        long b = data.leaveRequest(assignmentOn(7, "SCHEDULED"), staff, "REJECTED", manager);
        at(a, 20);
        at(b, 20);

        List<Long> ids = contentOf(dataOf(list("?size=2"))).stream().map(item -> idOf(item, "shiftRequestId")).toList();

        assertThat(ids).containsExactly(Math.max(a, b), Math.min(a, b));
    }

    // ---------------------------------------------------------------- lọc và phân trang

    @Test
    void theStatusFilterKeepsOnlyThatStatusAndStillOnlyTheCallers() {
        assertThat(ids(list("?status=PENDING"))).containsExactly(mine[3]);
        assertThat(ids(list("?status=REJECTED"))).containsExactly(mine[4], mine[1]);
        assertThat(ids(list("?status=APPROVED"))).containsExactly(mine[0]);
        assertThat(ids(list("?status=WITHDRAWN"))).containsExactly(mine[2]);
    }

    @Test
    void pagingReportsTotalsAndAPageBeyondTheEndIsEmpty() {
        Map<String, Object> first = dataOf(list("?page=0&size=2"));
        Map<String, Object> last = dataOf(list("?page=2&size=2"));
        Map<String, Object> beyond = dataOf(list("?page=9&size=2"));

        assertThat(contentOf(first)).hasSize(2);
        assertThat(first).containsEntry("totalElements", 5).containsEntry("totalPages", 3).containsEntry("size", 2);
        assertThat(contentOf(last)).extracting(item -> idOf(item, "shiftRequestId")).containsExactly(mine[0]);
        assertThat(contentOf(beyond)).isEmpty();
        assertThat(beyond).containsEntry("totalElements", 5);
    }

    @Test
    void aNegativePageAndAnOutOfRangeSizeAreClampedNotRejected() {
        Map<String, Object> negative = dataOf(list("?page=-4"));
        Map<String, Object> tiny = dataOf(list("?size=0"));
        Map<String, Object> huge = dataOf(list("?size=100000"));

        assertThat(negative).containsEntry("page", 0);
        assertThat(tiny).containsEntry("size", 1);
        assertThat(contentOf(tiny)).hasSize(1);
        assertThat(huge).containsEntry("size", 100);
    }

    @Test
    void anUnknownStatusOrANonNumericPageIsMalformed400() {
        for (String query : List.of("?status=FOO", "?page=abc", "?size=1.5")) {
            ResponseEntity<Map<String, Object>> response = list(query);

            assertThat(response.getStatusCode().value()).as(query).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
        }
    }

    @Test
    void aStaffMemberWithoutRequestsGetsAnEmptyPage() {
        long newcomer = data.staff("CARETAKER", branch);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, REQUESTS, token(newcomer));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(response))).isEmpty();
        assertThat(dataOf(response)).containsEntry("totalElements", 0);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> list(String query) {
        return call(HttpMethod.GET, REQUESTS + query, token);
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return contentOf(dataOf(response)).stream().map(item -> idOf(item, "shiftRequestId")).toList();
    }

    private long assignmentOn(int dayOffset, String status) {
        return data.assignment(branch, staff, morning, DAY.plusDays(dayOffset), status, "SELF", manager);
    }

    /** Đặt {@code created_at} xác định (T0 + {@code hours} giờ) để thứ tự không phụ thuộc đồng hồ tường. */
    private long at(long requestId, int hours) {
        jdbc.update("UPDATE shift_requests SET created_at = ? WHERE id = ?", T0.plusHours(hours), requestId);
        return requestId;
    }
}
