package com.petcare.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.petcare.module.ai.AiItSupport;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * Đường kích hoạt nền của ST22 khi {@code app.ai.indexing.enabled = true} (07 §3, docs/adr/0130): nạp sau commit của
 * UC93, KHÔNG nạp khi transaction rollback, nút "Nạp lại" thử lại tài liệu FAILED, lượt quét khi khởi động và của job
 * định kỳ, đồng bộ phác đồ chỉ mỗi {@code ai.index.catalog_sync_minutes} phút. Embedding là lớp giả. Clock ở năm 2041
 * (IT không có job quét không cận dưới: era tương lai). Mọi test chờ luồng nền xong rồi mới dọn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.ai.indexing.enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KnowledgeIndexTriggerIT.TestBeans.class})
class KnowledgeIndexTriggerIT {

    private static final String TAG = "KnowledgeIndexTriggerIT";
    private static final Instant T0 = Instant.parse("2041-03-14T08:30:00Z");
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
    @Autowired
    private KnowledgeIndexJob job;

    private AppointmentItSupport data;
    private AiItSupport support;
    private String manager;
    private final List<Long> vaccineTypes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        data = new AppointmentItSupport(jdbc);
        support = new AiItSupport(jdbc, TAG);
        manager = new TransactionTemplate(transactionManager).execute(
                status -> sessions.open(data.staff("SUPER_MANAGER", null), "203.0.113.9", TAG)).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_fail_commit ON ai_documents");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_fail_commit()");
        support.cleanUp();
        support.deleteCatalogDocuments();
        vaccineTypes.forEach(id -> {
            jdbc.update("DELETE FROM vaccination_protocols WHERE vaccine_type_id = ?", id);
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id);
        });
        data.cleanUp();
    }

    private String status(long id) {
        return (String) support.row(id).get("index_status");
    }

    private long createDraft(String suffix) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", support.title(suffix));
        request.put("topic", "CARE");
        request.put("species", "ALL");
        request.put("content", AiItSupport.content(2));
        request.put("sourceNote", "Nguồn thử (IT)");
        request.put("reviewerName", "BS. Thử nghiệm (IT)");
        request.put("reviewedOn", "2026-11-05");
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, BASE, request);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(201);
        return ((Number) ((Map<?, ?>) response.getBody().get("data")).get("documentId")).longValue();
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(manager);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    // ---------------------------------------------------------------- sau commit của UC93

    @Test
    void publishingIndexesTheDocumentInTheBackgroundAfterCommit() {
        long id = createDraft("publish");
        assertThat(embeddings.calls()).as("nháp chưa được nạp").isZero();

        assertThat(call(HttpMethod.POST, BASE + "/" + id + "/publish", null).getStatusCode().value()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("INDEXED"));
        assertThat(support.row(id).get("indexed_version")).isEqualTo(1);
        assertThat(support.chunkCount(id)).isEqualTo(2);
    }

    @Test
    void editingAPublishedDocumentReindexesItToTheNewVersion() {
        long id = createDraft("edit");
        call(HttpMethod.POST, BASE + "/" + id + "/publish", null);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("INDEXED"));

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("content", AiItSupport.content(3));
        patch.put("reviewerName", "BS. Người Mới");
        patch.put("reviewedOn", "2026-11-10");
        assertThat(call(HttpMethod.PATCH, BASE + "/" + id, patch).getStatusCode().value()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(support.row(id).get("indexed_version")).isEqualTo(2);
            assertThat(status(id)).isEqualTo("INDEXED");
        });
        assertThat(support.chunks(id)).extracting(c -> c.get("doc_version")).containsExactly(2, 2, 2);
    }

    /**
     * Lỗi xảy ra <b>lúc commit</b> (constraint trigger hoãn), tức SAU khi {@code reindex} đã phát {@code AiIndexRequested}:
     * chỉ khi listener thật sự chờ {@code AFTER_COMMIT} thì lượt nạp không bắt đầu. (Lỗi audit xảy ra trước khi phát event
     * nên không phân biệt được hai cách.) Tài liệu đã {@code PUBLISHED} để lượt nạp, nếu lỡ chạy, có việc để làm.
     */
    @Test
    void aTransactionThatFailsAtCommitNeverStartsIndexing() {
        long id = support.document("rollback", AiItSupport.content(2), "PUBLISHED", 1, 0, "FAILED");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: lỗi lúc commit';
                END $$""");
        jdbc.execute("CREATE CONSTRAINT TRIGGER ai_it_fail_commit AFTER UPDATE ON ai_documents DEFERRABLE INITIALLY "
                + "DEFERRED FOR EACH ROW WHEN (NEW.index_status = 'PENDING') EXECUTE FUNCTION ai_it_fail_commit()");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, BASE + "/" + id + "/reindex", null);

        assertThat(response.getStatusCode().is5xxServerError()).as(String.valueOf(response.getBody())).isTrue();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(
                () -> assertThat(embeddings.calls()).isZero());
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void reindexRetriesADocumentThatFailed() {
        long id = createDraft("retry");
        embeddings.failNext(1, new AiProviderException(ChatErrorKind.PROVIDER_ERROR, "Nhà cung cấp AI trả HTTP 503"));
        call(HttpMethod.POST, BASE + "/" + id + "/publish", null);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("FAILED"));
        assertThat((String) support.row(id).get("index_error")).contains("PROVIDER_ERROR");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, BASE + "/" + id + "/reindex", null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(id)).isEqualTo("INDEXED"));
        assertThat(support.row(id).get("index_error")).isNull();
    }

    // ---------------------------------------------------------------- quét khi khởi động và định kỳ

    @Test
    void theStartupScanQueuesEveryPublishedDocumentWithoutAnUpToDateIndex() {
        long pending = support.document("pending", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        long current = support.document("current", AiItSupport.content(2), "PUBLISHED", 1, 1, "INDEXED");
        long draft = support.document("draft", AiItSupport.content(2), "DRAFT", 1, 0, "PENDING");

        job.onApplicationReady();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(pending)).isEqualTo("INDEXED"));
        assertThat(support.chunkCount(current)).as("đã khớp phiên bản, không nạp lại").isZero();
        assertThat(support.chunkCount(draft)).isZero();
    }

    @Test
    void theJobSyncsTheCatalogOnlyOncePerConfiguredIntervalThenQueuesTheIndexing() {
        long type = jdbc.queryForObject("INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id",
                Long.class, TAG + " vaccine " + UUID.randomUUID().toString().substring(0, 6));
        vaccineTypes.add(type);
        long dose = jdbc.queryForObject("""
                INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks)
                VALUES ('DOG', ?, 1, 21, 6) RETURNING id""", Long.class, type);

        job.run();

        long docId = jdbc.queryForObject("SELECT id FROM ai_documents WHERE vaccine_type_id = ?", Long.class, type);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(status(docId)).isEqualTo("INDEXED"));

        jdbc.update("UPDATE vaccination_protocols SET interval_days = 28 WHERE id = ?", dose);
        clock.advance(Duration.ofMinutes(59));
        job.run();
        assertThat(support.row(docId).get("version")).as("chưa đủ 60 phút [CFG] ai.index.catalog_sync_minutes").isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        job.run();

        assertThat(support.row(docId).get("version")).isEqualTo(2);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(
                () -> assertThat(support.row(docId).get("indexed_version")).isEqualTo(2));
    }
}
