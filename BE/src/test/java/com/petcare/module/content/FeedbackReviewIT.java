package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Xem và xử lý feedback — content-v1 #20–22 (UC84; BR-DG-02, 03, 04) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class FeedbackReviewIT extends ContentItSupport {

    private static final String PATH = "/api/feedbacks";

    private long branchA;
    private long branchB;
    private long customerId;
    private long superManagerId;
    private String superManager;
    private String managerA;
    private String managerB;
    private long inA;
    private long inB;
    private long noBranch;

    @BeforeEach
    void setUp() {
        branchA = newBranch("ACTIVE");
        branchB = newBranch("ACTIVE");
        superManagerId = staff("SUPER_MANAGER", null);
        superManager = token(superManagerId);
        managerA = token(staff("BRANCH_MANAGER", branchA));
        managerB = token(staff("BRANCH_MANAGER", branchB));
        customerId = onlineCustomer(false)[1];
        inA = feedback(branchA, "BRANCH_SERVICE", "2004-03-10T01:00:00Z");
        inB = feedback(branchB, "BRANCH_SERVICE", "2004-03-10T02:00:00Z");
        noBranch = feedback(null, "WEBSITE", "2004-03-11T02:00:00Z");
    }

    private long feedback(Long branchId, String topic, String createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO feedbacks (customer_id, topic, branch_id, rating, content, status, created_at, updated_at)
                VALUES (?, ?, ?, 4, 'Góp ý đủ dài để hợp lệ.', 'NEW', CAST(? AS timestamptz), CAST(? AS timestamptz))
                RETURNING id
                """, Long.class, customerId, topic, branchId, createdAt, createdAt);
    }

    private List<Long> ids(String query, String token) {
        Map<String, Object> page = data(call(HttpMethod.GET, PATH + query, token, null), HttpStatus.OK);
        return pageContent(page).stream().map(f -> number(f.get("feedbackId")))
                .filter(id -> id == inA || id == inB || id == noBranch).toList();
    }

    // ---------------------------------------------------------------- phạm vi

    @Test
    void superManagerSeesAllIncludingNoBranchNewestFirst() {
        assertThat(ids("?size=100", superManager)).containsExactly(noBranch, inB, inA);
    }

    @Test
    void branchManagerSeesOnlyOwnBranchNeverNoBranch() {
        assertThat(ids("?size=100", managerA)).containsExactly(inA);
        assertThat(ids("?size=100", managerB)).containsExactly(inB);
    }

    @Test
    void branchManagerAskingForAnotherBranchOrOpeningItIs403() {
        assertError(call(HttpMethod.GET, PATH + "?branchId=" + branchB, managerA, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.GET, PATH + "/" + inB, managerA, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.GET, PATH + "/" + noBranch, managerA, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.POST, PATH + "/" + inB + "/resolve", managerA, Map.of("resolutionNote", "ok")),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT status FROM feedbacks WHERE id = ?", String.class, inB))
                .isEqualTo("NEW");
    }

    @Test
    void otherRolesAndCustomersAreForbidden() {
        for (String role : List.of("ADMIN", "RECEPTIONIST", "VET", "CARETAKER")) {
            String token = token(staff(role, branchA));
            assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, PATH + "/" + inA, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, PATH, token(onlineCustomer(false)[0]), null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void filtersByBranchStatusTopicAndVietnamDayRange() {
        jdbc.update("UPDATE feedbacks SET status = 'SEEN' WHERE id = ?", inB);

        assertThat(ids("?size=100&branchId=" + branchB, superManager)).containsExactly(inB);
        assertThat(ids("?size=100&status=SEEN", superManager)).containsExactly(inB);
        assertThat(ids("?size=100&topic=WEBSITE", superManager)).containsExactly(noBranch);
        // 2004-03-10T17:00Z = 00:00 ngày 11/03 giờ VN: inA (01:00Z) và inB (02:00Z) là 10/03, noBranch (11/03 02:00Z) là 11/03.
        assertThat(ids("?size=100&from=2004-03-10&to=2004-03-10", superManager)).containsExactly(inB, inA);
        assertThat(ids("?size=100&from=2004-03-11&to=2004-03-11", superManager)).containsExactly(noBranch);
        assertError(call(HttpMethod.GET, PATH + "?from=2004-03-12&to=2004-03-10", superManager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, PATH + "?status=NOPE", superManager, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    // ---------------------------------------------------------------- mở lần đầu: NEW → SEEN

    @Test
    void openingNewMarksSeenOnceAndShowsCustomerAndBranchNames() {
        Map<String, Object> opened = data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);

        assertThat(opened).containsOnlyKeys("feedbackId", "customerId", "customerName", "topic", "branchId",
                        "branchName", "rating", "content", "status", "seenAt", "resolvedBy", "resolvedAt",
                        "resolutionNote", "createdAt")
                .containsEntry("status", "SEEN").containsEntry("seenAt", "2004-03-10T03:00:00Z")
                .containsEntry("resolutionNote", null);
        assertThat((String) opened.get("customerName")).startsWith("Khách content IT");
        assertThat((String) opened.get("branchName")).startsWith("ContentIT chi nhánh");

        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> again = data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);
        assertThat(again).containsEntry("seenAt", "2004-03-10T03:00:00Z").containsEntry("status", "SEEN");
    }

    @Test
    void openingSeenOrResolvedWritesNothing() {
        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);
        String before = jdbc.queryForObject("SELECT xmin::text FROM feedbacks WHERE id = ?", String.class, inA);

        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT xmin::text FROM feedbacks WHERE id = ?", String.class, inA))
                .isEqualTo(before);
    }

    @Test
    void twoConcurrentFirstOpensStampSeenAtOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<ResponseEntity<Map<String, Object>>>> opens = List.of(
                    CompletableFuture.supplyAsync(() -> call(HttpMethod.GET, PATH + "/" + inA, superManager, null),
                            pool),
                    CompletableFuture.supplyAsync(() -> call(HttpMethod.GET, PATH + "/" + inA, managerA, null), pool));
            for (CompletableFuture<ResponseEntity<Map<String, Object>>> open : opens) {
                assertThat(data(open.get(15, TimeUnit.SECONDS), HttpStatus.OK)).containsEntry("status", "SEEN")
                        .containsEntry("seenAt", "2004-03-10T03:00:00Z");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM feedbacks WHERE id = ? AND seen_at = ?", Long.class,
                    inA, Timestamp.from(java.time.Instant.parse("2004-03-10T03:00:00Z")))).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknownFeedbackIs404() {
        assertError(call(HttpMethod.GET, PATH + "/999999999", superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(call(HttpMethod.POST, PATH + "/999999999/resolve", superManager, Map.of("resolutionNote", "ok")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- xử lý: SEEN → RESOLVED

    @Test
    void resolveRecordsWhoWhenAndTheInternalNote() {
        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);
        clock.advance(Duration.ofMinutes(2));

        Map<String, Object> resolved = data(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA,
                Map.of("resolutionNote", "  Đã gọi lại khách xin lỗi.  ")), HttpStatus.OK);

        assertThat(resolved).containsEntry("status", "RESOLVED").containsEntry("resolutionNote",
                        "Đã gọi lại khách xin lỗi.").containsEntry("resolvedAt", "2004-03-10T03:02:00Z");
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM feedbacks WHERE id = ?", Long.class, inA)).isNotNull();
    }

    @Test
    void resolveRequiresANonBlankNote() {
        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);

        assertRule(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, Map.of("resolutionNote", "   ")),
                "BR-DG-04");
        assertError(callRaw(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, "{}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA,
                Map.of("resolutionNote", "n".repeat(2001))), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM feedbacks WHERE id = ?", String.class, inA))
                .isEqualTo("SEEN");
    }

    @Test
    void resolveNewOrAlreadyResolvedIs409() {
        assertError(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, Map.of("resolutionNote", "ok")),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);
        data(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, Map.of("resolutionNote", "ok")),
                HttpStatus.OK);
        assertError(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, Map.of("resolutionNote", "lại")),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void customerNeverSeesTheNoteAfterResolve() {
        long[] mine = onlineCustomer(false);
        long own = jdbc.queryForObject("""
                INSERT INTO feedbacks (customer_id, topic, content, status) VALUES (?, 'WEBSITE',
                'Góp ý của riêng khách này.', 'NEW') RETURNING id
                """, Long.class, mine[1]);
        data(call(HttpMethod.GET, PATH + "/" + own, superManager, null), HttpStatus.OK);
        data(call(HttpMethod.POST, PATH + "/" + own + "/resolve", superManager,
                Map.of("resolutionNote", "Ghi chú nội bộ")), HttpStatus.OK);

        List<Map<String, Object>> shown = list(call(HttpMethod.GET, "/api/me/feedbacks", token(mine[0]), null),
                HttpStatus.OK);

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("status", "RESOLVED").doesNotContainKey("resolutionNote");
        assertThat(shown.toString()).doesNotContain("Ghi chú nội bộ");
    }
}
