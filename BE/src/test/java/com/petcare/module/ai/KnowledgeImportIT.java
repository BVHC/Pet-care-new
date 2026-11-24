package com.petcare.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.petcare.module.ai.service.DocumentExtractor;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.exception.BusinessRuleViolationException;
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

    /** Bật thì bộ trích báo hết giờ 20 giây ngay (không chờ thật): kiểm "hết giờ thì không tài liệu nào được tạo". */
    private static final AtomicBoolean STALL = new AtomicBoolean();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        DocumentExtractor documentExtractor() {
            return new DocumentExtractor() {
                @Override
                public <T> T run(Callable<T> task) {
                    if (STALL.get()) {
                        throw new BusinessRuleViolationException("BR-AI-29", "Hết thời gian trích văn bản (quá 20 giây)");
                    }
                    return super.run(task);
                }
            };
        }

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
        STALL.set(false);
        // PUT cấu hình ghi updated_by = tài khoản ADMIN của ca; phải gỡ trước khi data.cleanUp() xóa tài khoản (FK system_configs_updated_by_fkey)
        jdbc.update("UPDATE system_configs SET updated_by = NULL WHERE key = 'ai.ingest.max_file_kb'");
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

    // ---------------------------------------------------------------- T89: external_id, hết giờ, trần [CFG]

    private static String fileId(byte[] file, int part) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file);
            return "FILE-" + HexFormat.of().formatHex(digest).substring(0, 16) + "-" + part;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nội dung không trùng file nào đã nạp (chỉ mục duy nhất của external_id trải trên cả bảng). */
    private static byte[] unique(String text) {
        return (text + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map<String, Object>> setLimit(String adminToken, int kb) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/system-configs/ai.ingest.max_file_kb", HttpMethod.PUT,
                new HttpEntity<>(Map.of("value", String.valueOf(kb)), headers), MAP);
    }

    @Test
    void aFileGetsTheExternalIdOfItsHashAndPartNumber() {
        byte[] file = unique("Cho ăn hai bữa mỗi ngày.");

        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " id.md", file));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> doc = documents(response).get(0);
        assertThat(doc).containsEntry("externalId", fileId(file, 1));
        assertThat(jdbc.queryForObject("SELECT external_id FROM ai_documents WHERE id = ?", String.class,
                ((Number) doc.get("documentId")).longValue())).isEqualTo(fileId(file, 1));
    }

    @Test
    void importingTheSameFileAgainIsRefusedWith409AndCreatesNothingMore() {
        byte[] file = unique("Nội dung nạp hai lần.");
        long first = ((Number) documents(call(manager, request(TAG + " lan-mot.md", file))).get(0).get("documentId")).longValue();

        ResponseEntity<Map<String, Object>> again = call(manager, request(TAG + " lan-hai.md", file));

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).containsEntry("errorCode", "CONCURRENCY_CONFLICT");
        assertThat((String) again.getBody().get("message")).contains("#" + first).endsWith("(BR-AI-30)");
        assertThat(count(TAG + "%")).as("không thêm nháp nào").isEqualTo(1);
    }

    @Test
    void aSplitFileHasOneIdPerPartAndRunningItAgainRefusesTheWholeFile() {
        byte[] file = ("## Mục\n" + "x".repeat(9_000) + "\n").repeat(5).concat("// " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map<String, Object>> first = call(manager, request(TAG + " dai-id.md", file));
        List<Map<String, Object>> docs = documents(first);
        ResponseEntity<Map<String, Object>> again = call(manager, request(TAG + " dai-id.md", file));

        assertThat(docs).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i < docs.size(); i++) {
            assertThat(docs.get(i)).containsEntry("externalId", fileId(file, i + 1));
        }
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(count(TAG + " dai-id (phần%")).isEqualTo(docs.size());
    }

    @Test
    void theFileIdentityIsItsRawBytesNotTheNormalizedTextNorTheName() {
        String text = "Dòng một.\nDòng hai. " + UUID.randomUUID() + "\n";
        byte[] lf = text.getBytes(StandardCharsets.UTF_8);
        byte[] crlf = text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        assertThat(call(manager, request(TAG + " lf.md", lf)).getStatusCode().value()).isEqualTo(201);
        assertThat(call(manager, request(TAG + " crlf.md", crlf)).getStatusCode().value())
                .as("cùng văn bản sau chuẩn hóa nhưng khác byte: là file khác").isEqualTo(201);
        assertThat(call(manager, request(TAG + " doi-ten.md", lf)).getStatusCode().value())
                .as("cùng byte, khác tên: là file đã nạp").isEqualTo(409);
        assertThat(call(manager, request(TAG + " lf.md", unique("Cùng tên, nội dung khác"))).getStatusCode().value())
                .as("cùng tên, khác byte: nạp được").isEqualTo(201);
    }

    @Test
    void anArchivedDocumentKeepsItsExternalIdSoTheSameFileStillGives409() {
        byte[] file = unique("Nội dung rồi lưu trữ.");
        long id = ((Number) documents(call(manager, request(TAG + " luu-tru.md", file))).get(0).get("documentId")).longValue();
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED' WHERE id = ?", id);

        ResponseEntity<Map<String, Object>> again = call(manager, request(TAG + " luu-tru-lai.md", file));

        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat((String) again.getBody().get("message")).contains("#" + id);
    }

    @Test
    void aBadFileGets400BeforeTheDuplicateCheck() {
        byte[] file = unique("Đã nạp thật");
        assertThat(call(manager, request(TAG + " that.md", file)).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map<String, Object>> fake = call(manager, request(TAG + " gia.pdf", file));

        assertThat(fake.getStatusCode().value()).as("sai phần mở rộng thắng trùng nội dung").isEqualTo(400);
    }

    @Test
    void aTimeoutDuringExtractionGives400AndLeavesNoDocument() {
        STALL.set(true);

        ResponseEntity<Map<String, Object>> response = call(manager, request(TAG + " cham.md", unique("Nội dung chậm")));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("Hết thời gian trích văn bản").endsWith("(BR-AI-29)");
        assertThat(count(TAG + "%")).isZero();
    }

    @Test
    void theSizeLimitIsTheConfigValueReadOnEveryImportSoAChangeNeedsNoRestart() {
        String admin = token(data.staff("ADMIN", null));
        byte[] atLimit = "x".repeat(256 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] oneMore = ("y".repeat(256 * 1024) + "z").getBytes(StandardCharsets.UTF_8);
        try {
            assertThat(setLimit(admin, 256).getStatusCode().value()).isEqualTo(200);

            assertThat(call(manager, request(TAG + " dung-tran.txt", atLimit)).getStatusCode().value())
                    .as("đúng trần được nhận").isEqualTo(201);
            ResponseEntity<Map<String, Object>> over = call(manager, request(TAG + " qua-tran.txt", oneMore));
            assertThat(over.getStatusCode().value()).as("trần + 1 byte").isEqualTo(400);
            assertThat((String) over.getBody().get("message")).contains("tối đa 256 kB").endsWith("(BR-AI-29)");

            assertThat(setLimit(admin, 512).getStatusCode().value()).isEqualTo(200);
            assertThat(call(manager, request(TAG + " qua-tran.txt", oneMore)).getStatusCode().value())
                    .as("nâng trần có hiệu lực ngay, không khởi động lại").isEqualTo(201);
        } finally {
            setLimit(admin, 2048);
        }
    }

    @Test
    void theLimitRangeIsEnforcedByTheConfigMechanism() {
        String admin = token(data.staff("ADMIN", null));
        try {
            assertThat(setLimit(admin, 255).getStatusCode().value()).isEqualTo(400);
            assertThat(setLimit(admin, 10_241).getStatusCode().value()).isEqualTo(400);
            assertThat(setLimit(admin, 10_240).getStatusCode().value()).isEqualTo(200);
        } finally {
            setLimit(admin, 2048);
        }
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
