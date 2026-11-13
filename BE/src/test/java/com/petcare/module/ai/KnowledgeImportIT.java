package com.petcare.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * ai-v1 #14 {@code POST /api/ai/documents/import} (BR-AI-29, docs/adr/0134 D1) trên bean thật: file → nháp {@code MANUAL},
 * không xuất bản, không vào truy hồi, audit như #6, phân quyền, một transaction cho cả file. Đọc từng kiểu file và các
 * trường hợp từ chối chi tiết nằm ở {@code DocumentFileReaderTest}. Era <b>năm 2047</b>: IT không có job quét không cận dưới.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KnowledgeImportIT.TestBeans.class})
class KnowledgeImportIT {

    private static final String TAG = "KnowledgeImportIT";
    private static final String URL = "/api/ai/documents/import";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(java.time.Instant.parse("2047-03-14T08:30:00Z"));
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

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private String manager;
    private long managerId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        managerId = data.staff("SUPER_MANAGER", null);
        manager = token(managerId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ai_chunks WHERE document_id IN (SELECT id FROM ai_documents WHERE title LIKE ?)", TAG + "%");
        jdbc.update("DELETE FROM ai_documents WHERE title LIKE ?", TAG + "%");
        data.cleanUp();
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(URL, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private static Map<String, Object> request(String fileName, byte[] bytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", fileName);
        body.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
        body.put("topic", "NUTRITION");
        body.put("species", "DOG");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody().get("data")).get("documents");
    }

    private long count(String like) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_documents WHERE title LIKE ?", Long.class, like);
    }

    @Test
    void aMarkdownFileBecomesOneDraftManualDocumentWithoutReviewerAndNotInRetrieval() {
        byte[] file = (("# Dinh dưỡng chó\n\n" + "Cho ăn hai bữa mỗi ngày. ").repeat(3)).getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " dinh-duong-cho.md", file));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        List<Map<String, Object>> docs = documents(response);
        assertThat(docs).hasSize(1);
        Map<String, Object> doc = docs.get(0);
        assertThat(doc).containsEntry("status", "DRAFT").containsEntry("sourceType", "MANUAL")
                .containsEntry("topic", "NUTRITION").containsEntry("species", "DOG").containsEntry("title", TAG + " dinh-duong-cho");
        assertThat(doc.get("reviewerName")).isNull();
        assertThat((String) doc.get("sourceNote")).startsWith("Nạp từ file " + TAG + " dinh-duong-cho (MD,");
        Map<String, Object> row = jdbc.queryForMap("SELECT status, source_type, index_status, reviewer_name FROM ai_documents WHERE id = ?",
                ((Number) doc.get("documentId")).longValue());
        assertThat(row).containsEntry("status", "DRAFT").containsEntry("source_type", "MANUAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_documents WHERE status = 'PUBLISHED' AND title LIKE ?", Long.class,
                TAG + "%")).as("không có gì vào truy hồi trước khi xuất bản").isZero();
    }

    @Test
    void aDocxFileIsImportedWithHeadingsAsMarkdownSections() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph heading = doc.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Vệ sinh tai");
            doc.createParagraph().createRun().setText("Lau tai nhẹ nhàng bằng gạc ẩm.");
            doc.write(out);
            docx = out.toByteArray();
        }

        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " goc.docx", docx));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String content = jdbc.queryForObject("SELECT content FROM ai_documents WHERE title = ?", String.class, TAG + " goc");
        assertThat(content).contains("## Vệ sinh tai").contains("Lau tai nhẹ nhàng");
    }

    @Test
    void aFileLongerThanTheLimitIsSplitIntoSeveralDraftPartsInOneTransaction() {
        String section = "## Mục\n" + "x".repeat(9_000) + "\n";
        byte[] file = section.repeat(5).getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " dai.md", file));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        List<Map<String, Object>> docs = documents(response);
        assertThat(docs).hasSizeGreaterThanOrEqualTo(3);
        assertThat(docs).extracting(d -> d.get("title")).first().isEqualTo(TAG + " dai (phần 1/" + docs.size() + ")");
        assertThat(count(TAG + " dai (phần%")).isEqualTo(docs.size());
    }

    @Test
    void importWritesTheSameAuditAsCreate() {
        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " audit.txt", "Nội dung".getBytes(StandardCharsets.UTF_8)));
        long id = ((Number) documents(response).get(0).get("documentId")).longValue();

        Map<String, Object> row = jdbc.queryForMap("SELECT action, actor_account_id FROM audit_logs WHERE entity_type = 'ai_documents' AND entity_id = ?", id);

        assertThat(row).containsEntry("action", "AI_DOCUMENT_CREATED").containsEntry("actor_account_id", managerId);
    }

    @Test
    void invalidFilesAreRejectedWith400AndCreateNothing() {
        assertThat(call(manager, request(TAG + " gia.pdf", "không phải pdf".getBytes(StandardCharsets.UTF_8))).getStatusCode().value()).isEqualTo(400);
        assertThat(call(manager, request(TAG + " chay.exe", "x".getBytes(StandardCharsets.UTF_8))).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> notBase64 = request(TAG + " a.md", new byte[] {1});
        notBase64.put("contentBase64", "***không-phải-base64***");
        assertThat(call(manager, notBase64).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> noTopic = request(TAG + " b.md", "x".getBytes(StandardCharsets.UTF_8));
        noTopic.remove("topic");
        assertThat(call(manager, noTopic).getStatusCode().value()).isEqualTo(400);
        assertThat(count(TAG + "%")).isZero();
    }

    @Test
    void onlySuperManagerMayImport() {
        Map<String, Object> body = request(TAG + " q.md", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(call(null, body).getStatusCode().value()).isEqualTo(401);
        assertThat(call(token(data.staff("ADMIN", null)), body).getStatusCode().value()).isEqualTo(403);
        assertThat(call(token(data.customerAccount()), body).getStatusCode().value()).isEqualTo(403);
        assertThat(count(TAG + "%")).isZero();
    }
}
