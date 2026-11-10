package com.petcare.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * UC93 (ai-v1 #5–#11) và FSM #13 trên bean thật, không stand-in (docs/07 §9, BR-AI-13, 14, 16, 17; docs/adr/0130). Mọi
 * trạng thái nguồn đi bằng endpoint (nháp = tạo; {@code PUBLISHED} = tạo rồi xuất bản; {@code ARCHIVED} = tạo rồi lưu
 * trữ). Ma trận sự kiện × trạng thái {@link #everyEventFromEveryStateFollowsTable03} là bản sao độc lập của bảng §9.
 * ST22 không chạy ở đây (profile test tắt); {@code KnowledgeIndexerIT} và {@code KnowledgeIndexTriggerIT} lo phần đó.
 * Era <b>năm 2039</b>: IT không có job quét không cận dưới.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AiDocumentFsmIT.TestBeans.class})
class AiDocumentFsmIT {

    private static final String TAG = "AiDocumentFsmIT";
    private static final Instant T0 = Instant.parse("2039-03-14T08:30:00Z");
    private static final String BASE = "/api/ai/documents";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FakeEmbeddingClient embeddings;

    private AppointmentItSupport data;
    private AiItSupport support;
    private TransactionTemplate tx;
    private String manager;
    private long managerId;
    private final List<Long> vaccineTypes = new ArrayList<>();
    private int seq;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        support = new AiItSupport(jdbc, TAG);
        managerId = data.staff("SUPER_MANAGER", null);
        manager = token(managerId);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_fail_audit ON audit_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_fail_audit()");
        support.cleanUp();
        support.deleteCatalogDocuments();
        vaccineTypes.forEach(id -> jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id));
        data.cleanUp();
    }

    // ================================================================ phân quyền

    @Test
    void onlySuperManagerCanUseTheSevenEndpoints() {
        long branch = data.branch("ACTIVE");
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("ADMIN", token(data.staff("ADMIN", null)));
        tokens.put("RECEPTIONIST", token(data.staff("RECEPTIONIST", branch)));
        tokens.put("CUSTOMER", token(data.customerAccount()));
        long id = doc("PUBLISHED");

        for (Map.Entry<String, String> caller : tokens.entrySet()) {
            for (Object[] call : List.of(
                    new Object[] {HttpMethod.GET, BASE, null},
                    new Object[] {HttpMethod.POST, BASE, body("x")},
                    new Object[] {HttpMethod.GET, BASE + "/" + id, null},
                    new Object[] {HttpMethod.PATCH, BASE + "/" + id, Map.of("topic", "HYGIENE")},
                    new Object[] {HttpMethod.POST, BASE + "/" + id + "/publish", null},
                    new Object[] {HttpMethod.POST, BASE + "/" + id + "/archive", null},
                    new Object[] {HttpMethod.POST, BASE + "/" + id + "/reindex", null})) {
                ResponseEntity<Map<String, Object>> response = call(caller.getValue(), (HttpMethod) call[0],
                        (String) call[1], call[2]);
                assertThat(response.getStatusCode().value()).as("%s %s %s", caller.getKey(), call[0], call[1])
                        .isEqualTo(403);
                assertThat(response.getBody()).containsEntry("errorCode", "ACCESS_DENIED");
            }
        }
        assertThat(support.row(id).get("status")).isEqualTo("PUBLISHED");
        assertThat(http.exchange(URI.create(http.getRootUri() + BASE), HttpMethod.GET, HttpEntity.EMPTY, MAP)
                .getStatusCode().value()).isEqualTo(401);
    }

    // ================================================================ tạo (Tài liệu#1)

    @Test
    void createMakesADraftAtVersion1WithoutIndexingAndWithoutASourceNote() {
        Map<String, Object> request = body("a");
        request.remove("sourceNote");
        request.remove("reviewerName");
        request.remove("reviewedOn");

        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE, request);

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        Map<String, Object> data = data(response);
        assertThat(data).containsEntry("sourceType", "MANUAL").containsEntry("status", "DRAFT")
                .containsEntry("version", 1).containsEntry("indexedVersion", 0).containsEntry("indexStatus", "PENDING")
                .containsEntry("chunkCount", 0).containsEntry("sourceNote", null).containsEntry("reviewerName", null)
                .containsEntry("reviewedOn", null).containsEntry("publishedAt", null).containsEntry("indexError", null);
        assertThat(data.get("documentId")).isNotNull();
        // V34 bắt source_note NOT NULL với MANUAL: nháp chưa có nguồn lưu chuỗi rỗng, API trả null (docs/adr/0130).
        assertThat(support.row(id(data)).get("source_note")).isEqualTo("");
        assertThat(embeddings.calls()).isZero();
    }

    static Stream<Object[]> brokenCreateBodies() {
        return Stream.of(
                new Object[] {"tiêu đề rỗng", "title", "  ", 400, "BUSINESS_RULE_VIOLATION", "BR-AI-14"},
                new Object[] {"tiêu đề quá 200 ký tự", "title", "t".repeat(201), 400, "BUSINESS_RULE_VIOLATION", "BR-AI-14"},
                new Object[] {"nội dung quá 20 000 ký tự", "content", "c".repeat(20_001), 400, "BUSINESS_RULE_VIOLATION", "BR-AI-14"},
                new Object[] {"thiếu nội dung", "content", null, 400, "VALIDATION_FAILED", null},
                new Object[] {"thiếu chủ đề", "topic", null, 400, "VALIDATION_FAILED", null},
                new Object[] {"thiếu loài", "species", null, 400, "VALIDATION_FAILED", null},
                new Object[] {"nguồn quá 500 ký tự", "sourceNote", "n".repeat(501), 400, "VALIDATION_FAILED", null},
                new Object[] {"tên người rà soát quá 100 ký tự", "reviewerName", "r".repeat(101), 400, "VALIDATION_FAILED", null},
                new Object[] {"chủ đề lạ", "topic", "KHONG_CO", 400, "MALFORMED_REQUEST", null},
                new Object[] {"ngày rà soát sai định dạng", "reviewedOn", "09/11/2026", 400, "MALFORMED_REQUEST", null});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenCreateBodies")
    void createRejectsBrokenInputAndWritesNothing(String name, String field, Object value, int status, String errorCode,
            String ruleId) {
        Map<String, Object> request = body("broken");
        request.put(field, value);

        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE, request);

        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", errorCode);
        if (ruleId != null) {
            assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_documents WHERE title LIKE ?", Integer.class,
                TAG + "%")).isZero();
    }

    // ================================================================ FSM #13: mọi cặp sự kiện × trạng thái

    @Test
    void everyEventFromEveryStateFollowsTable03() {
        // {sự kiện, trạng thái nguồn, mã HTTP, trạng thái sau}. Bản sao độc lập của bảng 07 §9 (#2–#6; #1 là "tạo", #7 là ST22).
        List<Object[]> table = List.of(
                new Object[] {"publish", "DRAFT", 200, "PUBLISHED"},       // #2
                new Object[] {"publish", "PUBLISHED", 409, "PUBLISHED"},
                new Object[] {"publish", "ARCHIVED", 200, "PUBLISHED"},    // #5
                new Object[] {"archive", "DRAFT", 200, "ARCHIVED"},        // #6
                new Object[] {"archive", "PUBLISHED", 200, "ARCHIVED"},    // #4
                new Object[] {"archive", "ARCHIVED", 409, "ARCHIVED"},
                new Object[] {"edit", "DRAFT", 200, "DRAFT"},              // sửa nháp, không phải chuyển trạng thái
                new Object[] {"edit", "PUBLISHED", 200, "PUBLISHED"},      // #3
                new Object[] {"edit", "ARCHIVED", 409, "ARCHIVED"},
                new Object[] {"reindex", "DRAFT", 409, "DRAFT"},
                new Object[] {"reindex", "PUBLISHED", 202, "PUBLISHED"},
                new Object[] {"reindex", "ARCHIVED", 409, "ARCHIVED"});

        for (Object[] row : table) {
            String event = (String) row[0];
            String from = (String) row[1];
            long id = doc(from);

            ResponseEntity<Map<String, Object>> response = switch (event) {
                case "edit" -> call(manager, HttpMethod.PATCH, BASE + "/" + id, Map.of("topic", "HYGIENE"));
                default -> call(manager, HttpMethod.POST, BASE + "/" + id + "/" + event, null);
            };

            assertThat(response.getStatusCode().value()).as("%s từ %s → %s", event, from, response.getBody())
                    .isEqualTo(row[2]);
            if ((int) row[2] == 409) {
                assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION");
            }
            assertThat(support.row(id).get("status")).as("trạng thái sau %s từ %s", event, from).isEqualTo(row[3]);
        }
    }

    // ================================================================ xuất bản (BR-AI-14, hai lớp kiểm soát)

    @Test
    void publishNeedsBothControlLayersAndNamesEveryMissingField() {
        Map<String, Object> noSource = body("a");
        noSource.remove("sourceNote");
        Map<String, Object> noReviewer = body("b");
        noReviewer.remove("reviewerName");
        Map<String, Object> noDate = body("c");
        noDate.remove("reviewedOn");
        Map<String, Object> neither = body("d");
        neither.remove("reviewerName");
        neither.remove("reviewedOn");

        for (Object[] c : List.of(new Object[] {noSource, "sourceNote"}, new Object[] {noReviewer, "reviewerName"},
                new Object[] {noDate, "reviewedOn"}, new Object[] {neither, "reviewerName"})) {
            long id = id(created(call(manager, HttpMethod.POST, BASE, c[0])));

            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);

            assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
            assertThat((String) response.getBody().get("message")).contains((String) c[1]).endsWith("(BR-AI-14)");
            assertThat(support.row(id).get("status")).isEqualTo("DRAFT");
        }
    }

    @Test
    void publishRejectsEmptyContent() {
        Map<String, Object> request = body("empty");
        request.put("content", "  ");
        long id = id(created(call(manager, HttpMethod.POST, BASE, request)));

        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-AI-14)");
    }

    @Test
    void publishQueuesTheDocumentForIndexingAndKeepsTheVersion() {
        long id = doc("DRAFT");

        Map<String, Object> published = data(call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null));

        assertThat(published).containsEntry("status", "PUBLISHED").containsEntry("version", 1)
                .containsEntry("indexedVersion", 0).containsEntry("indexStatus", "PENDING");
        assertThat((String) published.get("publishedAt")).startsWith("2039-03-14T");
    }

    @Test
    void republishingAnArchivedDocumentQueuesItAgainWithoutChangingTheVersionOrTheServingChunks() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);
        support.chunk(id, 1, 1, "đoạn đang phục vụ");
        call(manager, HttpMethod.POST, BASE + "/" + id + "/archive", null);

        Map<String, Object> republished = data(call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null));

        assertThat(republished).containsEntry("status", "PUBLISHED").containsEntry("version", 1)
                .containsEntry("indexedVersion", 1).containsEntry("indexStatus", "PENDING")
                .containsEntry("chunkCount", 1);
    }

    // ================================================================ sửa (Tài liệu#3)

    @Test
    void editingADraftChangesTheFieldsWithoutBumpingTheVersion() {
        long id = doc("DRAFT");

        Map<String, Object> edited = data(call(manager, HttpMethod.PATCH, BASE + "/" + id,
                Map.of("title", support.title("đổi tên"), "content", "Nội dung mới", "topic", "NUTRITION",
                        "species", "CAT")));

        assertThat(edited).containsEntry("title", support.title("đổi tên")).containsEntry("content", "Nội dung mới")
                .containsEntry("topic", "NUTRITION").containsEntry("species", "CAT").containsEntry("version", 1)
                .containsEntry("status", "DRAFT");
    }

    @Test
    void editingTheContentOfAPublishedDocumentNeedsAFreshReviewerAndBumpsTheVersion() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);
        support.chunk(id, 1, 1, "đoạn của phiên bản 1");

        ResponseEntity<Map<String, Object>> withoutReviewer = call(manager, HttpMethod.PATCH, BASE + "/" + id,
                Map.of("content", "Nội dung đã sửa hoàn toàn"));
        assertThat(withoutReviewer.getStatusCode().value()).as(String.valueOf(withoutReviewer.getBody())).isEqualTo(400);
        assertThat((String) withoutReviewer.getBody().get("message")).endsWith("(BR-AI-14)");
        assertThat(support.row(id).get("version")).isEqualTo(1);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("content", "Nội dung đã sửa hoàn toàn");
        request.put("reviewerName", "BS. Người Mới");
        request.put("reviewedOn", "2026-11-10");
        Map<String, Object> edited = data(call(manager, HttpMethod.PATCH, BASE + "/" + id, request));

        assertThat(edited).containsEntry("version", 2).containsEntry("indexStatus", "PENDING")
                .containsEntry("indexedVersion", 1).containsEntry("reviewerName", "BS. Người Mới")
                .containsEntry("reviewedOn", "2026-11-10").containsEntry("status", "PUBLISHED");
        assertThat(support.chunkCount(id)).as("đoạn cũ vẫn phục vụ tới khi ST22 hoán đổi").isEqualTo(1);
    }

    /**
     * BR-AI-14 chỉ đòi cập nhật người và ngày rà soát khi sửa <b>nội dung</b>; tiêu đề nằm trong văn bản đem nhúng
     * (BR-AI-15) nên vẫn tăng {@code version}, nhưng người rà soát cũ giữ nguyên. Khớp mock FE (T57, ghi chú A8).
     */
    @Test
    void editingOnlyTheTitleOfAPublishedDocumentBumpsTheVersionWithoutANewReviewer() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);

        Map<String, Object> edited = data(call(manager, HttpMethod.PATCH, BASE + "/" + id,
                Map.of("title", TAG + " tiêu đề mới")));

        assertThat(edited).containsEntry("version", 2).containsEntry("indexStatus", "PENDING")
                .containsEntry("indexedVersion", 1).containsEntry("title", TAG + " tiêu đề mới")
                .containsEntry("reviewerName", "BS. Thử nghiệm (IT)").containsEntry("reviewedOn", "2026-11-05");
    }

    @Test
    void editingOnlyTopicOrSpeciesOfAPublishedDocumentKeepsTheVersionAndTheIndex() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);

        Map<String, Object> edited = data(call(manager, HttpMethod.PATCH, BASE + "/" + id,
                Map.of("topic", "BEHAVIOR", "species", "DOG")));

        assertThat(edited).containsEntry("version", 1).containsEntry("indexStatus", "INDEXED")
                .containsEntry("topic", "BEHAVIOR").containsEntry("species", "DOG");
    }

    @Test
    void sendingTheSameContentIsNotAChange() {
        long id = doc("PUBLISHED");
        String content = (String) support.row(id).get("content");

        Map<String, Object> edited = data(call(manager, HttpMethod.PATCH, BASE + "/" + id, Map.of("content", content)));

        assertThat(edited).containsEntry("version", 1);
    }

    @Test
    void aPublishedDocumentCannotLoseItsSourceOrItsReviewer() {
        long id = doc("PUBLISHED");

        for (Map<String, Object> patch : List.<Map<String, Object>>of(Map.of("sourceNote", "  "),
                Map.of("reviewerName", " "))) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PATCH, BASE + "/" + id, patch);

            assertThat(response.getStatusCode().value()).as(String.valueOf(patch)).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-AI-14)");
        }
        assertThat(support.row(id).get("source_note")).isEqualTo("Nguồn thử (IT)");
    }

    @Test
    void catalogProtocolDocumentsCannotBeEditedOrPublishedByHand() {
        long type = jdbc.queryForObject("INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id",
                Long.class, TAG + " vaccine " + seq++);
        vaccineTypes.add(type);
        long id = jdbc.queryForObject("""
                INSERT INTO ai_documents (source_type, vaccine_type_id, title, topic, species, content, status)
                VALUES ('CATALOG_PROTOCOL', ?, ?, 'VACCINATION', 'DOG', 'Phác đồ', 'PUBLISHED') RETURNING id""",
                Long.class, type, support.title("phác đồ"));

        ResponseEntity<Map<String, Object>> edit = call(manager, HttpMethod.PATCH, BASE + "/" + id, Map.of("content", "x"));
        ResponseEntity<Map<String, Object>> publish = call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);

        for (ResponseEntity<Map<String, Object>> response : List.of(edit, publish)) {
            assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-AI-13)");
        }
        assertThat(support.row(id).get("content")).isEqualTo("Phác đồ");
        // Lưu trữ thì được (#4): A04 có thể không muốn chatbot dùng phác đồ.
        assertThat(call(manager, HttpMethod.POST, BASE + "/" + id + "/archive", null).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void editRejectsABlankOrTooLongTitleAndTooLongContent() {
        long id = doc("DRAFT");

        for (Map<String, Object> patch : List.<Map<String, Object>>of(Map.of("title", " "),
                Map.of("title", "t".repeat(201)), Map.of("content", "c".repeat(20_001)))) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PATCH, BASE + "/" + id, patch);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-AI-14)");
        }
        assertThat(support.row(id).get("title")).isEqualTo(support.title("nháp"));
    }

    @Test
    void unknownDocumentIs404AndAMalformedIdIs400() {
        for (Object[] c : List.of(new Object[] {HttpMethod.GET, ""}, new Object[] {HttpMethod.PATCH, ""},
                new Object[] {HttpMethod.POST, "/publish"}, new Object[] {HttpMethod.POST, "/archive"},
                new Object[] {HttpMethod.POST, "/reindex"})) {
            Object requestBody = c[0] == HttpMethod.PATCH ? Map.of("topic", "CARE") : null;

            ResponseEntity<Map<String, Object>> missing = call(manager, (HttpMethod) c[0],
                    BASE + "/" + Long.MAX_VALUE + c[1], requestBody);
            ResponseEntity<Map<String, Object>> malformed = call(manager, (HttpMethod) c[0], BASE + "/abc" + c[1],
                    requestBody);

            assertThat(missing.getStatusCode().value()).as("%s %s", c[0], c[1]).isEqualTo(404);
            assertThat(missing.getBody()).containsEntry("errorCode", "RESOURCE_NOT_FOUND");
            assertThat(malformed.getStatusCode().value()).as("%s %s", c[0], c[1]).isEqualTo(400);
            assertThat(malformed.getBody()).containsEntry("errorCode", "MALFORMED_REQUEST");
        }
    }

    // ================================================================ nạp lại (UC93 reindex)

    @Test
    void reindexQueuesAFailedDocumentAndIsIdempotent() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'FAILED', index_error = 'lỗi cũ' WHERE id = ?", id);

        for (int i = 0; i < 2; i++) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE + "/" + id + "/reindex", null);

            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(data(response)).containsEntry("indexStatus", "PENDING").containsEntry("indexError", null)
                    .containsEntry("version", 1).containsEntry("indexedVersion", 1).containsEntry("status", "PUBLISHED");
        }
        assertThat(embeddings.calls()).as("reindex chỉ xếp hàng, không nhúng ngay trong request").isZero();
    }

    // ================================================================ danh sách và chi tiết

    @Test
    void listFiltersSortsByLastEditAndClampsThePage() {
        long first = doc("DRAFT", "một");
        clock.advance(Duration.ofMinutes(1));
        long second = doc("ARCHIVED", "hai");
        clock.advance(Duration.ofMinutes(1));
        long third = doc("PUBLISHED", "ba");
        long fourth = doc("DRAFT", "bốn");
        String q = "?q=" + TAG.toLowerCase();

        List<Map<String, Object>> all = content(call(manager, HttpMethod.GET, BASE + q, null));

        assertThat(all).extracting(d -> id(d)).containsExactly(fourth, third, second, first);
        assertThat(all.get(0)).containsOnlyKeys("documentId", "sourceType", "title", "topic", "species", "status",
                "version", "indexStatus", "updatedAt");
        assertThat(content(call(manager, HttpMethod.GET, BASE + q + "&status=DRAFT", null))).extracting(d -> id(d))
                .containsExactly(fourth, first);
        assertThat(content(call(manager, HttpMethod.GET, BASE + q + "&status=ARCHIVED&topic=CARE&species=ALL", null)))
                .extracting(d -> id(d)).containsExactly(second);
        assertThat(content(call(manager, HttpMethod.GET, BASE + q + "&topic=NUTRITION", null))).isEmpty();
        assertThat(content(call(manager, HttpMethod.GET, BASE + "?q=%25_", null))).as("% và _ không là ký tự đại diện")
                .isEmpty();

        Map<String, Object> page = data(call(manager, HttpMethod.GET, BASE + q + "&size=2&page=1", null));
        assertThat((List<?>) page.get("content")).hasSize(2);
        assertThat(page).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 4)
                .containsEntry("totalPages", 2);
        assertThat(data(call(manager, HttpMethod.GET, BASE + q + "&size=0&page=-3", null)))
                .containsEntry("size", 1).containsEntry("page", 0);
        assertThat(data(call(manager, HttpMethod.GET, BASE + q + "&size=1000", null))).containsEntry("size", 100);
    }

    @Test
    void detailCountsOnlyTheChunksOfTheIndexedVersion() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET version = 3, indexed_version = 2, index_status = 'PENDING' WHERE id = ?", id);
        support.chunk(id, 1, 1, "phiên bản 1");
        support.chunk(id, 2, 1, "phiên bản 2 a");
        support.chunk(id, 2, 2, "phiên bản 2 b");

        Map<String, Object> detail = data(call(manager, HttpMethod.GET, BASE + "/" + id, null));

        assertThat(detail).containsEntry("chunkCount", 2).containsEntry("version", 3).containsEntry("indexedVersion", 2);
        assertThat(detail.get("content")).isNotNull();
    }

    /**
     * BR-AI-16, 07 §14.3: lưu trữ có hiệu lực ngay khi commit vì truy hồi lọc {@code status} trong SQL; đoạn vẫn còn,
     * nên xuất bản lại thì phục vụ tiếp (cùng phiên bản đã nạp) mà không cần chờ ST22.
     */
    @Test
    void archivingStopsRetrievalAtOnceAndRepublishingBringsTheSameChunksBack() {
        long id = doc("PUBLISHED");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);
        support.chunk(id, 1, 1, "đoạn truy hồi");
        float[] query = FakeEmbeddingClient.vectorOf("đoạn truy hồi");
        assertThat(support.retrieve(query)).hasSize(1);

        call(manager, HttpMethod.POST, BASE + "/" + id + "/archive", null);

        assertThat(support.retrieve(query)).isEmpty();
        assertThat(support.chunkCount(id)).isEqualTo(1);

        call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);

        assertThat(support.retrieve(query)).hasSize(1);
    }

    // ================================================================ audit (BR-AI-17) và transaction

    @Test
    void everyWriteIsAuditedWithBeforeAndAfterButNeverTheFullContent() {
        long id = id(created(call(manager, HttpMethod.POST, BASE, body("audit"))));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("topic", "HYGIENE");
        call(manager, HttpMethod.PATCH, BASE + "/" + id, patch);
        call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);
        call(manager, HttpMethod.POST, BASE + "/" + id + "/reindex", null);
        call(manager, HttpMethod.POST, BASE + "/" + id + "/archive", null);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, entity_type, actor_account_id, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'ai_documents' AND entity_id = ? ORDER BY id""", id);

        assertThat(rows).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_CREATED", "AI_DOCUMENT_UPDATED",
                "AI_DOCUMENT_PUBLISHED", "AI_DOCUMENT_REINDEX_REQUESTED", "AI_DOCUMENT_ARCHIVED");
        assertThat(rows).extracting(r -> r.get("actor_account_id")).containsOnly(managerId);
        assertThat(rows.get(0).get("before_data")).isNull();
        assertThat((String) rows.get(0).get("after_data")).contains("\"status\": \"DRAFT\"");
        assertThat((String) rows.get(1).get("before_data")).contains("\"topic\": \"CARE\"");
        assertThat((String) rows.get(1).get("after_data")).contains("\"topic\": \"HYGIENE\"");
        assertThat((String) rows.get(2).get("before_data")).contains("\"status\": \"DRAFT\"");
        assertThat((String) rows.get(2).get("after_data")).contains("\"status\": \"PUBLISHED\"")
                .contains("\"reviewerName\"");
        assertThat((String) rows.get(4).get("after_data")).contains("\"status\": \"ARCHIVED\"");
        for (Map<String, Object> row : rows) {
            assertThat(String.valueOf(row.get("after_data"))).contains("contentLength").doesNotContain("Nội dung thử");
        }
    }

    @Test
    void aFailedAuditRollsTheBusinessChangeBack() {
        long id = doc("DRAFT");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: lỗi ghi audit';
                END $$""");
        jdbc.execute("CREATE TRIGGER ai_it_fail_audit BEFORE INSERT ON audit_logs FOR EACH ROW "
                + "WHEN (NEW.action = 'AI_DOCUMENT_PUBLISHED') EXECUTE FUNCTION ai_it_fail_audit()");

        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null);

        assertThat(response.getStatusCode().is5xxServerError()).as(String.valueOf(response.getBody())).isTrue();
        assertThat(support.row(id).get("status")).isEqualTo("DRAFT");
        assertThat(support.row(id).get("published_at")).isNull();
    }

    /**
     * Khóa dòng của UC93 (docs/adr/0130): một transaction khác giữ {@code FOR NO KEY UPDATE} trên tài liệu và nâng
     * {@code version} lên 5; lệnh sửa phải <b>chờ</b> (thấy ở {@code pg_stat_activity}), rồi đọc sau khóa nên nâng tiếp
     * lên 6. Không khóa thì lệnh sửa đọc ngay phiên bản 1, ghi 2 đè mất bản 5.
     */
    @Test
    void editWaitsForTheRowLockAndNeverOverwritesAConcurrentChange() throws Exception {
        long id = doc("PUBLISHED");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM ai_documents WHERE id = ? FOR NO KEY UPDATE", id);
                jdbc.update("UPDATE ai_documents SET version = 5 WHERE id = ?", id);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> edit = pool.submit(() -> {
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("content", "Nội dung sửa song song");
                patch.put("reviewerName", "BS. Song Song");
                patch.put("reviewedOn", "2026-11-10");
                return call(manager, HttpMethod.PATCH, BASE + "/" + id, patch);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND datname = current_database()", Integer.class) == 0) {
                assertThat(System.nanoTime()).as("lệnh sửa phải chờ khóa dòng").isLessThan(deadline);
                Thread.sleep(50);
            }
            assertThat(edit.isDone()).isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertThat(edit.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(support.row(id).get("version")).isEqualTo(6);
    }

    // ================================================================ helpers

    private Map<String, Object> body(String suffix) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", support.title(suffix));
        request.put("topic", "CARE");
        request.put("species", "ALL");
        request.put("content", "Nội dung thử " + suffix);
        request.put("sourceNote", "Nguồn thử (IT)");
        request.put("reviewerName", "BS. Thử nghiệm (IT)");
        request.put("reviewedOn", "2026-11-05");
        return request;
    }

    private long doc(String state) {
        return doc(state, "nháp");
    }

    /** Tài liệu ở trạng thái {@code state} do endpoint dựng; tiêu đề {@code TAG nháp} khi không nêu hậu tố. */
    private long doc(String state, String suffix) {
        long id = id(created(call(manager, HttpMethod.POST, BASE, body(suffix))));
        switch (state) {
            case "PUBLISHED" -> assertThat(call(manager, HttpMethod.POST, BASE + "/" + id + "/publish", null)
                    .getStatusCode().value()).isEqualTo(200);
            case "ARCHIVED" -> assertThat(call(manager, HttpMethod.POST, BASE + "/" + id + "/archive", null)
                    .getStatusCode().value()).isEqualTo(200);
            default -> { }
        }
        return id;
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private static Map<String, Object> created(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return data(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).as(String.valueOf(response.getBody())).isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) data(response).get("content");
    }

    private static long id(Map<String, Object> data) {
        return ((Number) (data.containsKey("documentId") ? data.get("documentId") : data.get("id"))).longValue();
    }
}
