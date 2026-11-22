package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Yêu cầu nghỉ / đổi ca của chi nhánh (workforce-v1 #12; docs/adr/0138 bước 8) trên Postgres thật: chỉ chi nhánh của
 * người gọi (theo chi nhánh của <b>phân ca</b>), {@code status} vắng = {@code PENDING}, cũ nhất trước (trùng thời điểm
 * thì id nhỏ trước), phân trang và kẹp {@code page} / {@code size} (kể cả {@code page} quá lớn, từng là 500), ghép tên
 * đúng từng dòng kể cả người đã nghỉ / điều chuyển và ca đã ngừng dùng, không để lại dấu vết và không bị chặn bởi luồng
 * ghi đang giữ khóa. Era <b>năm 2073</b>. Quyền / xác thực: {@code ShiftRequestBranchListSecurityIT}; ảnh nhất quán:
 * {@code ShiftRequestBranchListSnapshotIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestBranchListIT.TestBeans.class})
class ShiftRequestBranchListIT extends ShiftRequestItSupport {

    private static final Instant NOW = Instant.parse("2073-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2073, 3, 21);
    private static final OffsetDateTime T0 = OffsetDateTime.of(2073, 3, 1, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final String LIST = "/api/shift-requests";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private long branchA;
    private long branchB;
    private long managerA;
    private long managerB;
    private long staffA;
    private long vetA;
    private long staffB;
    private long morning;
    private long afternoon;
    private long morningB;
    private String token;
    /** Yêu cầu {@code PENDING} của chi nhánh A theo thứ tự gửi (chỉ số 0 cũ nhất). */
    private long[] pending;
    private long approved;
    private long rejected;
    private long withdrawn;
    private long foreignPending;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        managerA = data.staff("BRANCH_MANAGER", branchA);
        managerB = data.staff("BRANCH_MANAGER", branchB);
        staffA = data.staff("RECEPTIONIST", branchA);
        vetA = data.staff("VET", branchA);
        staffB = data.staff("RECEPTIONIST", branchB);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
        morningB = definition(branchB, "Ca sáng B", "08:00", "12:00", true);
        token = token(managerA);

        pending = new long[3];
        pending[0] = at(data.leaveRequest(assignmentOf(branchA, staffA, morning, 1), staffA, "PENDING", null), 0);
        pending[1] = at(data.swapRequest(assignmentOf(branchA, vetA, morning, 2), vetA, afternoon, "PENDING", null), 1);
        pending[2] = at(data.leaveRequest(assignmentOf(branchA, staffA, morning, 3), staffA, "PENDING", null), 2);
        approved = at(data.leaveRequest(assignmentOf(branchA, staffA, morning, 4), staffA, "APPROVED", managerA), 3);
        rejected = at(data.leaveRequest(assignmentOf(branchA, vetA, morning, 5), vetA, "REJECTED", managerA), 4);
        withdrawn = at(data.leaveRequest(assignmentOf(branchA, staffA, morning, 6), staffA, "WITHDRAWN", null), 5);
        foreignPending = at(data.leaveRequest(assignmentOf(branchB, staffB, morningB, 1), staffB, "PENDING", null), 0);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.shutdownNow();
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- phạm vi, mặc định, thứ tự

    @Test
    void withoutAStatusItListsThePendingRequestsOfTheCallersBranchOldestFirstWithTheFifteenKeys() {
        ResponseEntity<Map<String, Object>> response = list("");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> page = dataOf(response);
        assertThat(ids(response)).containsExactly(pending[0], pending[1], pending[2]);
        assertThat(contentOf(page)).allSatisfy(item -> {
            assertThat(item.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
            assertThat(item).containsEntry("status", "PENDING").containsEntry("closedAt", null)
                    .containsEntry("decidedBy", null).containsEntry("decisionNote", null);
        });
        assertThat(page).containsEntry("totalElements", 3).containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalPages", 1);
        assertThat(ids(response)).doesNotContain(foreignPending);
    }

    @Test
    void anEmptyStatusParameterMeansPendingToo() {
        assertThat(ids(list("?status="))).isEqualTo(ids(list("")));
    }

    @Test
    void theStatusFilterKeepsOnlyThatStatusOfTheCallersBranch() {
        assertThat(ids(list("?status=PENDING"))).containsExactly(pending[0], pending[1], pending[2]);
        assertThat(ids(list("?status=APPROVED"))).containsExactly(approved);
        assertThat(ids(list("?status=REJECTED"))).containsExactly(rejected);
        assertThat(ids(list("?status=WITHDRAWN"))).containsExactly(withdrawn);
    }

    @Test
    void eachBranchManagerSeesOnlyTheirOwnBranchAndABranchIdParameterCannotWidenIt() {
        String other = token(managerB);

        assertThat(ids(call(HttpMethod.GET, LIST, other))).containsExactly(foreignPending);
        assertThat(ids(call(HttpMethod.GET, LIST + "?branchId=" + branchA, other))).containsExactly(foreignPending);
        assertThat(ids(list("?branchId=" + branchB))).containsExactly(pending[0], pending[1], pending[2]);
    }

    @Test
    void theOrderMatchesTheReferenceQueryAndTiesBreakByIdAscending() {
        long a = at(data.leaveRequest(assignmentOf(branchA, staffA, morning, 7), staffA, "PENDING", null), 30);
        long b = at(data.leaveRequest(assignmentOf(branchA, vetA, morning, 8), vetA, "PENDING", null), 30);

        List<Long> expected = jdbc.queryForList("""
                SELECT r.id FROM shift_requests r JOIN shift_assignments a ON a.id = r.assignment_id
                WHERE a.branch_id = ? AND r.status = 'PENDING' ORDER BY r.created_at, r.id""", Long.class, branchA);

        assertThat(ids(list(""))).isEqualTo(expected).endsWith(Math.min(a, b), Math.max(a, b));
    }

    // ---------------------------------------------------------------- phân trang và kẹp

    @Test
    void pagingReportsTotalsAndAPageBeyondTheEndIsEmpty() {
        Map<String, Object> first = dataOf(list("?page=0&size=2"));
        Map<String, Object> last = dataOf(list("?page=1&size=2"));
        Map<String, Object> beyond = dataOf(list("?page=9&size=2"));

        assertThat(contentOf(first)).extracting(item -> idOf(item, "shiftRequestId"))
                .containsExactly(pending[0], pending[1]);
        assertThat(first).containsEntry("totalElements", 3).containsEntry("totalPages", 2).containsEntry("size", 2);
        assertThat(contentOf(last)).extracting(item -> idOf(item, "shiftRequestId")).containsExactly(pending[2]);
        assertThat(contentOf(beyond)).isEmpty();
        assertThat(beyond).containsEntry("totalElements", 3);
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

    /** Spring Data JPA ném (500) khi page × size vượt int; {@code pageOf} kẹp nên cả ba danh sách trả trang rỗng 200. */
    @Test
    void aHugePageIsAnEmptyOkOnAllThreeWorkforceListsNotA500() {
        String staffToken = token(staffA);
        String range = "?from=" + DAY + "&to=" + DAY.plusDays(1);

        for (String paging : List.of("&page=2147483647", "&page=2147483647&size=100", "&page=21474837&size=100",
                "&page=21474836&size=101")) {
            ResponseEntity<Map<String, Object>> branchList = call(HttpMethod.GET, LIST + "?" + paging.substring(1),
                    token);
            ResponseEntity<Map<String, Object>> mine = call(HttpMethod.GET, REQUESTS + "?" + paging.substring(1),
                    staffToken);
            ResponseEntity<Map<String, Object>> schedule = call(HttpMethod.GET, ASSIGNMENTS + range + paging, token);

            assertThat(branchList.getStatusCode().value()).as("#12 %s", paging).isEqualTo(200);
            assertThat(contentOf(dataOf(branchList))).as("#12 %s", paging).isEmpty();
            assertThat(mine.getStatusCode().value()).as("#10 %s", paging).isEqualTo(200);
            assertThat(contentOf(dataOf(mine))).as("#10 %s", paging).isEmpty();
            assertThat(schedule.getStatusCode().value()).as("#4 %s", paging).isEqualTo(200);
            assertThat(contentOf(dataOf(schedule))).as("#4 %s", paging).isEmpty();
        }
    }

    @Test
    void anUnknownOrLowerCaseStatusAndANonNumericPageOrSizeAreMalformed400() {
        for (String query : List.of("?status=FOO", "?status=pending", "?page=abc", "?size=1.5")) {
            ResponseEntity<Map<String, Object>> response = list(query);

            assertThat(response.getStatusCode().value()).as(query).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
        }
    }

    // ---------------------------------------------------------------- ghép dữ liệu từng dòng

    @Test
    void everyRowOfAMixedPageCarriesItsOwnRequesterShiftAndDates() {
        List<Map<String, Object>> content = contentOf(dataOf(list("")));

        assertThat(content).hasSize(3);
        Map<String, Object> leave = content.get(0);
        Map<String, Object> swap = content.get(1);
        assertThat(idOf(leave, "shiftRequestId")).isEqualTo(pending[0]);
        assertThat(leave).containsEntry("requesterName", nameOf(staffA)).containsEntry("type", "LEAVE")
                .containsEntry("targetShiftDefinitionId", null).containsEntry("targetShiftName", null)
                .containsEntry("workDate", DAY.plusDays(1).toString());
        assertThat((String) leave.get("currentShiftName")).startsWith("Ca sáng");
        assertThat(idOf(swap, "shiftRequestId")).isEqualTo(pending[1]);
        assertThat(swap).containsEntry("requesterName", nameOf(vetA)).containsEntry("type", "SWAP")
                .containsEntry("targetShiftDefinitionId", (int) afternoon)
                .containsEntry("workDate", DAY.plusDays(2).toString());
        assertThat((String) swap.get("targetShiftName")).startsWith("Ca chiều");
        assertThat(content).allSatisfy(item -> {
            assertThat(item.get("workDate").toString()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(Instant.parse((String) item.get("createdAt"))).isNotNull();
        });
    }

    @Test
    void aClosedRequestShowsItsDeciderNoteAndCloseTime() {
        Map<String, Object> approvedItem = contentOf(dataOf(list("?status=APPROVED"))).get(0);
        Map<String, Object> rejectedItem = contentOf(dataOf(list("?status=REJECTED"))).get(0);
        Map<String, Object> withdrawnItem = contentOf(dataOf(list("?status=WITHDRAWN"))).get(0);

        assertThat(approvedItem).containsEntry("decidedBy", (int) managerA).containsEntry("decisionNote", null);
        assertThat(Instant.parse((String) approvedItem.get("closedAt"))).isNotNull();
        assertThat(rejectedItem).containsEntry("decidedBy", null).containsEntry("decisionNote", "Ghi chú");
        assertThat(Instant.parse((String) rejectedItem.get("closedAt"))).isNotNull();
        assertThat(withdrawnItem).containsEntry("decidedBy", null).containsEntry("decisionNote", null);
        assertThat(Instant.parse((String) withdrawnItem.get("closedAt"))).isNotNull();
    }

    /**
     * Người gửi đã điều chuyển sang chi nhánh B và bị vô hiệu hóa: yêu cầu (đã bị hệ thống từ chối, BR-NS-09) vẫn thuộc
     * chi nhánh A của phân ca, vẫn hiện tên người gửi, và B không thấy. Mô phỏng bằng SQL: việc điều chuyển / vô hiệu
     * hóa thật bằng API và listener có IT riêng (StaffLeftBranchIT), ở đây chỉ cần trạng thái dữ liệu sau đó.
     */
    @Test
    void aTransferredAndDisabledRequestersHistoryStaysWithTheOldBranchWithTheirName() {
        long mover = data.staff("RECEPTIONIST", branchA);
        long moverRequest = at(data.leaveRequest(assignmentOf(branchA, mover, morning, 9), mover, "REJECTED", null), 6);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, mover);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", mover);

        List<Map<String, Object>> rejectedInA = contentOf(dataOf(list("?status=REJECTED")));

        assertThat(rejectedInA).extracting(item -> idOf(item, "shiftRequestId")).contains(moverRequest);
        assertThat(rejectedInA).filteredOn(item -> idOf(item, "shiftRequestId") == moverRequest).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("requesterName", nameOf(mover))
                        .containsEntry("requesterId", (int) mover).containsEntry("decidedBy", null));
        assertThat(ids(call(HttpMethod.GET, LIST + "?status=REJECTED", token(managerB)))).doesNotContain(moverRequest);
    }

    @Test
    void shiftsDeactivatedAfterTheRequestWasSentStillShowTheirNames() {
        jdbc.update("UPDATE shift_definitions SET is_active = false WHERE id IN (?, ?)", morning, afternoon);

        List<Map<String, Object>> content = contentOf(dataOf(list("")));

        assertThat(content).hasSize(3);
        assertThat((String) content.get(0).get("currentShiftName")).startsWith("Ca sáng");
        assertThat((String) content.get(1).get("targetShiftName")).startsWith("Ca chiều");
    }

    @Test
    void aBranchWithoutRequestsGetsAnEmptyPage() {
        long branchC = data.branch();
        long managerC = data.staff("BRANCH_MANAGER", branchC);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, LIST, token(managerC));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contentOf(dataOf(response))).isEmpty();
        assertThat(dataOf(response)).containsEntry("totalElements", 0);
    }

    // ---------------------------------------------------------------- transaction: không dấu vết, không bị chặn

    @Test
    void listingLeavesNoTraceInRequestsAssignmentsOutboxOrAudit() {
        String before = traceOfEverything();

        for (String query : List.of("", "?status=APPROVED", "?status=REJECTED", "?status=WITHDRAWN", "?size=1&page=2",
                "?page=9999", "?status=FOO", "?page=2147483647")) {
            list(query);
        }
        call(HttpMethod.GET, LIST, token(staffA));
        call(HttpMethod.GET, LIST, null);

        assertThat(traceOfEverything()).isEqualTo(before);
    }

    /**
     * Một luồng ghi đang giữ khóa {@code accounts} và yêu cầu (đúng thứ tự khóa của rút / hủy) và đã sửa dòng chưa
     * commit: #12 là đọc MVCC nên trả ngay trạng thái đã commit (vẫn {@code PENDING}) thay vì chờ; sau khi luồng ghi
     * commit thì lần đọc sau thấy trạng thái mới.
     */
    @Test
    void aWriterHoldingTheLocksNeitherBlocksTheListNorIsSeenUntilItCommits() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, staffA);
            jdbc.queryForList("SELECT id FROM shift_requests WHERE id = ? FOR NO KEY UPDATE", Long.class, pending[0]);
            jdbc.update("UPDATE shift_requests SET status = 'WITHDRAWN', closed_at = now() WHERE id = ?", pending[0]);
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<Long>> whileLocked = pool.submit(() -> ids(list("")));

            assertThat(whileLocked.get(10, TimeUnit.SECONDS)).containsExactly(pending[0], pending[1], pending[2]);
        } finally {
            release.countDown();
        }
        writer.get(10, TimeUnit.SECONDS);

        assertThat(ids(list(""))).containsExactly(pending[1], pending[2]);
        assertThat(ids(list("?status=WITHDRAWN"))).contains(pending[0]);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> list(String query) {
        return call(HttpMethod.GET, LIST + query, token);
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return contentOf(dataOf(response)).stream().map(item -> idOf(item, "shiftRequestId")).toList();
    }

    private long assignmentOf(long branch, long staff, long definition, int dayOffset) {
        return data.assignment(branch, staff, definition, DAY.plusDays(dayOffset), "SCHEDULED", "SELF", null);
    }

    private String nameOf(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class,
                accountId);
    }

    /** Đặt {@code created_at} xác định (T0 + {@code hours} giờ) để thứ tự không phụ thuộc đồng hồ tường. */
    private long at(long requestId, int hours) {
        jdbc.update("UPDATE shift_requests SET created_at = ? WHERE id = ?", T0.plusHours(hours), requestId);
        return requestId;
    }

    /**
     * Ảnh chụp mọi thứ #12 có thể (nhưng không được) đụng tới: từng dòng yêu cầu và phân ca của hai chi nhánh kể cả
     * {@code xmin} (mọi UPDATE đổi nó), số dòng outbox của người trong era và số dòng audit của quản lý.
     */
    private String traceOfEverything() {
        return jdbc.queryForList("""
                SELECT r.id || ':' || r.status || ':' || r.reason || ':' || coalesce(r.decided_by::text, '') || ':'
                       || coalesce(r.decision_note, '') || ':' || coalesce(r.closed_at::text, '') || ':'
                       || r.created_at::text || ':' || r.updated_at::text || ':' || r.xmin::text
                FROM shift_requests r JOIN shift_assignments a ON a.id = r.assignment_id
                WHERE a.branch_id IN (?, ?) ORDER BY r.id""", String.class, branchA, branchB)
                + "|assignments=" + jdbc.queryForList("""
                SELECT id || ':' || status || ':' || coalesce(status_changed_at::text, '') || ':' || updated_at::text
                       || ':' || xmin::text
                FROM shift_assignments WHERE branch_id IN (?, ?) ORDER BY id""", String.class, branchA, branchB)
                + "|outbox=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification_outbox
                WHERE recipient_account_id IN (?, ?, ?, ?, ?)""", Integer.class, managerA, managerB, staffA, vetA,
                staffB)
                + "|audit=" + jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE actor_account_id IN (?, ?, ?, ?, ?)""", Integer.class, managerA,
                managerB, staffA, vetA, staffB);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ luồng chính nhả khóa");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
