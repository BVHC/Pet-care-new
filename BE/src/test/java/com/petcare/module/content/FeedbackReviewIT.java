package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Xem feedback, chỉ đọc, không trạng thái — content-v1 #20–21 (UC84; BR-DG-02, 03; ADR-0122) trên Postgres 17 thật. */
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
                INSERT INTO feedbacks (customer_id, topic, branch_id, rating, content, created_at, updated_at)
                VALUES (?, ?, ?, 4, 'Góp ý đủ dài để hợp lệ.', CAST(? AS timestamptz), CAST(? AS timestamptz))
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
    void filtersByBranchTopicAndVietnamDayRange() {
        assertThat(ids("?size=100&branchId=" + branchB, superManager)).containsExactly(inB);
        assertThat(ids("?size=100&topic=WEBSITE", superManager)).containsExactly(noBranch);
        // 2004-03-10T17:00Z = 00:00 ngày 11/03 giờ VN: inA (01:00Z) và inB (02:00Z) là 10/03, noBranch (11/03 02:00Z) là 11/03.
        assertThat(ids("?size=100&from=2004-03-10&to=2004-03-10", superManager)).containsExactly(inB, inA);
        assertThat(ids("?size=100&from=2004-03-11&to=2004-03-11", superManager)).containsExactly(noBranch);
        assertError(call(HttpMethod.GET, PATH + "?from=2004-03-12&to=2004-03-10", superManager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    /** ADR-0122: tham số {@code status} của bản cũ không còn nghĩa; client cũ còn gửi thì bị bỏ qua, không lỗi. */
    @Test
    void theRemovedStatusFilterIsIgnored() {
        assertThat(ids("?size=100&status=SEEN", superManager)).containsExactly(noBranch, inB, inA);
        assertThat(ids("?size=100&status=NOPE", superManager)).containsExactly(noBranch, inB, inA);
    }

    // ---------------------------------------------------------------- chi tiết: chỉ đọc

    @Test
    void detailShowsCustomerAndBranchNamesAndNoStatusFields() {
        Map<String, Object> opened = data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);

        assertThat(opened).containsOnlyKeys("feedbackId", "customerId", "customerName", "topic", "branchId",
                "branchName", "rating", "content", "createdAt");
        assertThat((String) opened.get("customerName")).startsWith("Khách content IT");
        assertThat((String) opened.get("branchName")).startsWith("ContentIT chi nhánh");
        assertThat(opened).containsEntry("createdAt", "2004-03-10T01:00:00Z");
    }

    @Test
    void listItemsHaveNoStatusFieldsEither() {
        Map<String, Object> page = data(call(HttpMethod.GET, PATH + "?size=100", managerA, null), HttpStatus.OK);

        assertThat(pageContent(page)).isNotEmpty().allSatisfy(item -> assertThat(item).containsOnlyKeys(
                "feedbackId", "customerId", "customerName", "topic", "branchId", "branchName", "rating", "content",
                "createdAt"));
    }

    @Test
    void openingAFeedbackWritesNothing() {
        String before = jdbc.queryForObject("SELECT xmin::text FROM feedbacks WHERE id = ?", String.class, inA);

        data(call(HttpMethod.GET, PATH + "/" + inA, managerA, null), HttpStatus.OK);
        data(call(HttpMethod.GET, PATH + "/" + inA, superManager, null), HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT xmin::text FROM feedbacks WHERE id = ?", String.class, inA))
                .isEqualTo(before);
    }

    @Test
    void unknownFeedbackIs404() {
        assertError(call(HttpMethod.GET, PATH + "/999999999", superManager, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    /** ADR-0122: bỏ BR-DG-04 và endpoint đánh dấu đã xử lý (content-v1 22 → 21 endpoint). */
    @Test
    void theResolveEndpointIsGone() {
        assertThat(call(HttpMethod.POST, PATH + "/" + inA + "/resolve", managerA, Map.of("resolutionNote", "ok"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedbacks WHERE id = ?", Long.class, inA)).isEqualTo(1L);
    }
}
