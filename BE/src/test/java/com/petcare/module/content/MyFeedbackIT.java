package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;

/** Khách gửi và xem lại feedback — content-v1 #18, #19 (UC83; BR-DG-01, 02, BR-TK-19) trên Postgres 17 thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class})
class MyFeedbackIT extends ContentItSupport {

    private static final String PATH = "/api/me/feedbacks";

    @Autowired
    private SystemConfigService configs;

    private long branch;
    private long customerId;
    private String customer;

    @BeforeEach
    void setUp() {
        branch = newBranch("ACTIVE");
        long[] online = onlineCustomer(false);
        customerId = online[1];
        customer = token(online[0]);
    }

    @AfterEach
    void restoreLimit() {
        jdbc.update("UPDATE system_configs SET value = '5' WHERE key = 'feedback.max_per_day'");
        configs.reload();
    }

    private Map<String, Object> body(String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("topic", "WEBSITE");
        body.put("content", content);
        return body;
    }

    private ResponseEntity<Map<String, Object>> submit(String token, Map<String, Object> body) {
        return call(HttpMethod.POST, PATH, token, body);
    }

    private long countFor(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM feedbacks WHERE customer_id = ?", Long.class, id);
    }

    // ---------------------------------------------------------------- nội dung

    @Test
    void submitStoresNewFeedbackAndReturnsOnlyTheCustomerFields() {
        Map<String, Object> request = body("Trang web dễ dùng, đặt lịch nhanh.");
        request.put("rating", 5);

        Map<String, Object> created = data(submit(customer, request), HttpStatus.CREATED);

        assertThat(created).containsOnlyKeys("feedbackId", "topic", "branchId", "rating", "content", "status",
                        "createdAt")
                .containsEntry("status", "NEW").containsEntry("rating", 5).containsEntry("branchId", null)
                .containsEntry("createdAt", "2004-03-10T03:00:00Z");
    }

    @Test
    void contentIsNormalizedAndStrippedBeforeStoring() {
        String decomposed = java.text.Normalizer.normalize("  Dịch vụ rất tốt, cảm ơn phòng khám.  ",
                java.text.Normalizer.Form.NFD);

        Map<String, Object> created = data(submit(customer, body(decomposed)), HttpStatus.CREATED);

        assertThat(created).containsEntry("content", "Dịch vụ rất tốt, cảm ơn phòng khám.");
    }

    @Test
    void contentOfNineCodePointsWithEmojiIsBrDg01AndTenIsAccepted() {
        assertRule(submit(customer, body("😀😀😀😀😀😀😀😀😀")), "BR-DG-01");
        assertThat(submit(customer, body("😀😀😀😀😀😀😀😀😀😀")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertRule(submit(customer, body("   ngắn   ")), "BR-DG-01");
        assertRule(submit(customer, body("a".repeat(2001))), "BR-DG-01");
        assertThat(submit(customer, body("a".repeat(2000))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countFor(customerId)).isEqualTo(2L);
    }

    @Test
    void ratingOutsideOneToFiveIsBrDg01() {
        for (int rating : List.of(0, 6, -1)) {
            Map<String, Object> request = body("Nội dung đủ dài để hợp lệ.");
            request.put("rating", rating);
            assertRule(submit(customer, request), "BR-DG-01");
        }
        Map<String, Object> request = body("Nội dung đủ dài để hợp lệ.");
        request.put("rating", 1);
        assertThat(submit(customer, request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void missingTopicOrContentIs400() {
        assertError(callRaw(HttpMethod.POST, PATH, customer, "{\"content\":\"Nội dung đủ dài.\"}"),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, PATH, customer, "{\"topic\":\"WEBSITE\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, PATH, customer, "{\"topic\":\"LA\",\"content\":\"Nội dung đủ dài.\"}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    // ---------------------------------------------------------------- chi nhánh

    @Test
    void branchServiceNeedsAnActiveBranch() {
        Map<String, Object> noBranch = body("Nhân viên chi nhánh rất nhiệt tình.");
        noBranch.put("topic", "BRANCH_SERVICE");
        assertRule(submit(customer, noBranch), "BR-DG-01");

        Map<String, Object> unknown = new HashMap<>(noBranch);
        unknown.put("branchId", 999_999_999L);
        assertError(submit(customer, unknown), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        Map<String, Object> draft = new HashMap<>(noBranch);
        draft.put("branchId", newBranch("DRAFT"));
        assertError(submit(customer, draft), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        Map<String, Object> ok = new HashMap<>(noBranch);
        ok.put("branchId", branch);
        Map<String, Object> created = data(submit(customer, ok), HttpStatus.CREATED);
        assertThat(number(created.get("branchId"))).isEqualTo(branch);
        assertThat(countFor(customerId)).isEqualTo(1L);
    }

    @Test
    void websiteWithAValidBranchIsStoredWithIt() {
        Map<String, Object> request = body("Góp ý về website có kèm chi nhánh.");
        request.put("branchId", branch);

        assertThat(number(data(submit(customer, request), HttpStatus.CREATED).get("branchId"))).isEqualTo(branch);
    }

    // ---------------------------------------------------------------- BR-TK-19, quyền

    @Test
    void linkDecisionPendingIsBrTk19AndWritesNothing() {
        long[] pending = onlineCustomer(true);

        assertRule(submit(token(pending[0]), body("Nội dung đủ dài để hợp lệ.")), "BR-TK-19");
        assertThat(countFor(pending[1])).isZero();
    }

    @Test
    void staffAndAnonymousCannotSubmitOrListMine() {
        String receptionist = token(staff("RECEPTIONIST", branch));

        assertError(submit(receptionist, body("Nội dung đủ dài để hợp lệ.")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, receptionist, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(submit(null, body("Nội dung đủ dài để hợp lệ.")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- giới hạn mỗi ngày

    @Test
    void sixthFeedbackTodayIsBrDg01WithTheNextVietnamMidnight() {
        for (int i = 0; i < 5; i++) {
            data(submit(customer, body("Góp ý số " + i + " của khách hàng.")), HttpStatus.CREATED);
        }

        ResponseEntity<Map<String, Object>> sixth = submit(customer, body("Góp ý thứ sáu của khách hàng."));

        assertRule(sixth, "BR-DG-01");
        assertThat((String) sixth.getBody().get("message")).contains("5 feedback").contains("00:00 ngày 11/03/2004");
        assertThat(countFor(customerId)).isEqualTo(5L);
    }

    @Test
    void limitResetsAtVietnamMidnight() {
        clock.set(Instant.parse("2004-03-10T16:59:00Z"));
        String fresh = token(onlineCustomer(false)[0]);
        for (int i = 0; i < 5; i++) {
            data(submit(fresh, body("Góp ý số " + i + " trước nửa đêm.")), HttpStatus.CREATED);
        }
        assertRule(submit(fresh, body("Góp ý thứ sáu trước nửa đêm.")), "BR-DG-01");

        clock.set(Instant.parse("2004-03-10T17:00:00Z"));
        String afterMidnight = token(onlineCustomer(false)[0]);
        assertThat(submit(afterMidnight, body("Góp ý đầu tiên của ngày mới.")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        clock.set(Instant.parse("2004-03-10T17:00:30Z"));
        assertThat(submit(fresh, body("Góp ý sau nửa đêm của khách cũ.")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void limitIsReadFromConfigAtCheckTime() {
        jdbc.update("UPDATE system_configs SET value = '2' WHERE key = 'feedback.max_per_day'");
        configs.reload();

        data(submit(customer, body("Góp ý số một của khách hàng.")), HttpStatus.CREATED);
        data(submit(customer, body("Góp ý số hai của khách hàng.")), HttpStatus.CREATED);
        ResponseEntity<Map<String, Object>> third = submit(customer, body("Góp ý số ba của khách hàng."));

        assertRule(third, "BR-DG-01");
        assertThat((String) third.getBody().get("message")).contains("2 feedback");
    }

    /** Hai request cùng lúc khi khách đã có 4 feedback: advisory 1003 chỉ cho đúng một cái qua. */
    @Test
    void concurrentSubmitAtLimitOnlyOneWins() throws Exception {
        for (int i = 0; i < 4; i++) {
            data(submit(customer, body("Góp ý số " + i + " của khách hàng.")), HttpStatus.CREATED);
        }
        long accountKey = customerId % Integer.MAX_VALUE;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CompletableFuture<Void> holding = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        try {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(1003, ?)", Integer.class,
                                (int) accountKey);
                        holding.complete(null);
                        release.join();
                    }), pool);
            holding.get(10, TimeUnit.SECONDS);

            List<CompletableFuture<ResponseEntity<Map<String, Object>>>> requests = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String text = "Góp ý đồng thời số " + i + " của khách.";
                requests.add(CompletableFuture.supplyAsync(() -> submit(customer, body(text)), pool));
            }
            Thread.sleep(1500);
            release.complete(null);
            holder.get(10, TimeUnit.SECONDS);

            List<HttpStatus> statuses = new ArrayList<>();
            for (CompletableFuture<ResponseEntity<Map<String, Object>>> request : requests) {
                statuses.add(HttpStatus.valueOf(request.get(15, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
            assertThat(countFor(customerId)).isEqualTo(5L);
        } finally {
            release.complete(null);
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- xem lại

    @Test
    void listMineIsNewestFirstWithoutInternalFieldsAndOnlyMine() {
        long[] other = onlineCustomer(false);
        data(submit(token(other[0]), body("Góp ý của khách khác nữa.")), HttpStatus.CREATED);
        data(submit(customer, body("Góp ý đầu tiên của tôi.")), HttpStatus.CREATED);
        clock.advance(Duration.ofSeconds(30));
        data(submit(customer, body("Góp ý thứ hai của tôi.")), HttpStatus.CREATED);
        jdbc.update("UPDATE feedbacks SET status = 'RESOLVED', resolution_note = 'ghi chú nội bộ', "
                + "resolved_by = ?, resolved_at = now(), seen_at = now() WHERE customer_id = ? "
                + "AND content LIKE '%đầu tiên%'", staff("SUPER_MANAGER", null), customerId);

        List<Map<String, Object>> mine = list(call(HttpMethod.GET, PATH, customer, null), HttpStatus.OK);

        assertThat(mine).hasSize(2);
        assertThat(mine.get(0)).containsEntry("content", "Góp ý thứ hai của tôi.");
        assertThat(mine.get(1)).containsEntry("status", "RESOLVED");
        for (Map<String, Object> item : mine) {
            assertThat(item).containsOnlyKeys("feedbackId", "topic", "branchId", "rating", "content", "status",
                    "createdAt");
        }
    }
}
