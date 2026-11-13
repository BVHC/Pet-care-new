package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.ai.AiItSupport;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;

/**
 * ST22 phần đồng bộ tài liệu {@code CATALOG_PROTOCOL} với phác đồ tiêm đang hoạt động (07 §5.3 bước 2, BR-AI-13, FSM #13
 * dòng #7, #4, #5; docs/adr/0130): mỗi loại vaccine một tài liệu {@code PUBLISHED}, mã băm đổi thì dựng lại nội dung và
 * tăng {@code version}, loại vaccine hết phác đồ thì tài liệu bị lưu trữ tự động (có audit) và quay lại khi phác đồ có
 * lại; tài liệu do người quản lý lưu trữ ({@code updated_by} khác null) thì không bao giờ đụng. Dùng chung context với {@link KnowledgeIndexerIT}. Dữ liệu danh
 * mục do IT dựng và xóa; mọi tài liệu phác đồ bị xóa sau mỗi test vì khóa ngoại tới {@code vaccine_types} sẽ chặn IT khác.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KnowledgeIndexerIT.TestBeans.class})
class CatalogProtocolSyncIT {

    private static final String TAG = "CatalogProtocolSyncIT";

    @Autowired
    private CatalogProtocolSync sync;
    @Autowired
    private CatalogProtocolWriter writer;
    @Autowired
    private ChunkRetriever retriever;
    @Autowired
    private FakeEmbeddingClient embeddings;
    @Autowired
    private JdbcTemplate jdbc;

    private AiItSupport support;
    private AppointmentItSupport data;
    private final List<Long> types = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new AiItSupport(jdbc, TAG);
        data = new AppointmentItSupport(jdbc);
        embeddings.reset();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_reject_catalog ON ai_documents");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_reject_catalog()");
        support.deleteCatalogDocuments();
        support.cleanUp();
        types.forEach(id -> {
            jdbc.update("DELETE FROM vaccination_protocols WHERE vaccine_type_id = ?", id);
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id);
        });
        data.cleanUp();
    }

    private long vaccineType(String species, String name) {
        long id = jdbc.queryForObject("INSERT INTO vaccine_types (name, species) VALUES (?, ?) RETURNING id", Long.class,
                TAG + " " + name + " " + UUID.randomUUID().toString().substring(0, 6), species);
        types.add(id);
        return id;
    }

    private long dose(String species, long type, int number, int minAgeWeeks, int intervalDays, boolean boarding,
            boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO vaccination_protocols (species, vaccine_type_id, dose_number, interval_days, min_age_weeks,
                        required_for_boarding, is_active) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                species, type, number, intervalDays, minAgeWeeks, boarding, active);
    }

    private Map<String, Object> docOf(long type) {
        return jdbc.queryForMap("SELECT * FROM ai_documents WHERE vaccine_type_id = ?", type);
    }

    private long docCount(long... typeIds) {
        long count = 0;
        for (long type : typeIds) {
            count += jdbc.queryForObject("SELECT count(*) FROM ai_documents WHERE vaccine_type_id = ?", Long.class, type);
        }
        return count;
    }

    /** Đã nạp phiên bản 1 với một đoạn có chữ {@code text}: chatbot truy hồi được tài liệu này. */
    private void serve(long documentId, String text) {
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", documentId);
        support.chunk(documentId, 1, 1, text);
    }

    private List<Long> servedBy(String text) {
        return retriever.nearest(FakeEmbeddingClient.vectorOf(text), 50).stream().map(RetrievedChunk::documentId).toList();
    }

    private List<Map<String, Object>> auditOf(long documentId) {
        return jdbc.queryForList("""
                SELECT action, actor_account_id, actor_email, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'ai_documents' AND entity_id = ? ORDER BY id""", documentId);
    }

    @Test
    void createsOnePublishedDocumentPerVaccineTypeWithTheScheduleInVietnamese() {
        long type = vaccineType("DOG", "Dại");
        dose("DOG", type, 1, 6, 21, true, true);
        dose("DOG", type, 2, 9, 365, false, true);

        sync.sync();

        Map<String, Object> doc = docOf(type);
        assertThat(doc).containsEntry("source_type", "CATALOG_PROTOCOL").containsEntry("status", "PUBLISHED")
                .containsEntry("topic", "VACCINATION").containsEntry("species", "DOG").containsEntry("version", 1)
                .containsEntry("indexed_version", 0).containsEntry("index_status", "PENDING")
                .containsEntry("reviewer_name", null).containsEntry("reviewed_on", null)
                .containsEntry("updated_by", null);
        assertThat(doc.get("published_at")).isNotNull();
        assertThat((String) doc.get("title")).startsWith("Phác đồ tiêm ").contains(TAG + " Dại");
        String content = (String) doc.get("content");
        assertThat(content).contains("Mũi 1", "Mũi 2", "6 tuần", "9 tuần", "21 ngày", "365 ngày", "chó");
        assertThat(content).contains("Bắt buộc khi gửi lưu trú: có").contains("Bắt buộc khi gửi lưu trú: không");
        assertThat((String) doc.get("content_hash")).hasSize(64).isEqualTo(Sha256.hex(content));
    }

    @Test
    void isIdempotentWhenTheScheduleDidNotChange() {
        long type = vaccineType("DOG", "Dại");
        dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        Map<String, Object> first = docOf(type);

        sync.sync();

        assertThat(docCount(type)).isEqualTo(1);
        assertThat(docOf(type)).containsEntry("version", 1).containsEntry("content_hash", first.get("content_hash"));
    }

    @Test
    void rebuildsTheContentAndBumpsTheVersionWhenTheScheduleChangesWhileTheOldChunksKeepServing() {
        long type = vaccineType("CAT", "Tổng hợp");
        long dose1 = dose("CAT", type, 1, 8, 21, false, true);
        sync.sync();
        long id = (Long) docOf(type).get("id");
        jdbc.update("UPDATE ai_documents SET indexed_version = 1, index_status = 'INDEXED' WHERE id = ?", id);
        support.chunk(id, 1, 1, "đoạn của phác đồ cũ");
        String oldHash = (String) docOf(type).get("content_hash");
        jdbc.update("UPDATE vaccination_protocols SET interval_days = 28 WHERE id = ?", dose1);

        sync.sync();

        Map<String, Object> doc = docOf(type);
        assertThat(doc).containsEntry("version", 2).containsEntry("index_status", "PENDING")
                .containsEntry("indexed_version", 1).containsEntry("species", "CAT")
                .containsEntry("status", "PUBLISHED");
        assertThat((String) doc.get("content")).contains("28 ngày").doesNotContain("21 ngày").contains("mèo");
        assertThat(doc.get("content_hash")).isNotEqualTo(oldHash);
        assertThat(support.chunkCount(id)).as("đoạn cũ vẫn phục vụ tới khi ST22 hoán đổi").isEqualTo(1);
    }

    @Test
    void inactiveDosesAreNotPartOfTheDocument() {
        long type = vaccineType("DOG", "Care");
        dose("DOG", type, 1, 6, 21, false, true);
        dose("DOG", type, 2, 9, 30, false, false);

        sync.sync();

        assertThat((String) docOf(type).get("content")).contains("Mũi 1").doesNotContain("Mũi 2");
    }

    @Test
    void aVaccineTypeWithoutAnyActiveDoseGetsNoDocument() {
        long type = vaccineType("DOG", "Rỗng");
        dose("DOG", type, 1, 6, 21, false, false);

        sync.sync();

        assertThat(docCount(type)).isZero();
    }

    @Test
    void anArchivedDocumentIsLeftAloneWhateverTheSchedule() {
        long type = vaccineType("DOG", "Dại");
        long dose1 = dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED', updated_by = ? WHERE vaccine_type_id = ?",
                data.staff("SUPER_MANAGER", null), type);   // như UC93 lưu trữ tay: ghi người thực hiện
        String content = (String) docOf(type).get("content");
        jdbc.update("UPDATE vaccination_protocols SET interval_days = 99 WHERE id = ?", dose1);

        sync.sync();

        assertThat(docOf(type)).containsEntry("status", "ARCHIVED").containsEntry("version", 1)
                .containsEntry("content", content);
    }

    @Test
    void aFailureOnOneVaccineTypeDoesNotStopTheOthers() {
        long bad = vaccineType("DOG", "Lỗi");
        long good = vaccineType("DOG", "Tốt");
        dose("DOG", bad, 1, 6, 21, false, true);
        dose("DOG", good, 1, 6, 21, false, true);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_reject_catalog() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: từ chối chèn tài liệu phác đồ';
                END $$""");
        jdbc.execute("CREATE TRIGGER ai_it_reject_catalog BEFORE INSERT ON ai_documents FOR EACH ROW WHEN (NEW.vaccine_type_id = "
                + bad + ") EXECUTE FUNCTION ai_it_reject_catalog()");

        sync.sync();

        assertThat(docCount(bad)).isZero();
        assertThat(docCount(good)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- hết phác đồ → lưu trữ tự động (ADR-0130 #5)

    @Test
    void archivesTheDocumentOfAVaccineTypeThatLostAllItsDosesWithASystemAuditAndTheChatbotStopsServingIt() {
        long type = vaccineType("DOG", "Dại");
        long dose1 = dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        long id = (Long) docOf(type).get("id");
        serve(id, "dai-thu-nghiem-hai-lan-moi-nam");
        assertThat(servedBy("dai-thu-nghiem-hai-lan-moi-nam")).as("trước khi ngừng phác đồ").contains(id);
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE id = ?", dose1);

        sync.sync();

        assertThat(docOf(type)).containsEntry("status", "ARCHIVED").containsEntry("updated_by", null);
        assertThat(servedBy("dai-thu-nghiem-hai-lan-moi-nam")).as("chatbot không còn trả phác đồ đã ngừng")
                .doesNotContain(id);
        List<Map<String, Object>> audit = auditOf(id);
        assertThat(audit).as("tạo và dựng lại nội dung vẫn không audit; chỉ lưu trữ có").hasSize(1);
        Map<String, Object> row = audit.get(0);
        assertThat(row).containsEntry("action", "AI_DOCUMENT_ARCHIVED").containsEntry("actor_account_id", null)
                .containsEntry("actor_email", null);
        assertThat((String) row.get("before_data")).contains("\"status\": \"PUBLISHED\"");
        assertThat((String) row.get("after_data")).contains("\"status\": \"ARCHIVED\"")
                .contains("\"sourceType\": \"CATALOG_PROTOCOL\"", "contentLength", "contentSha256");
    }

    @Test
    void keepsTheDocumentPublishedWhileAtLeastOneDoseStaysActive() {
        long type = vaccineType("DOG", "Dại");
        dose("DOG", type, 1, 6, 21, false, true);
        long dose2 = dose("DOG", type, 2, 9, 365, false, true);
        sync.sync();
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE id = ?", dose2);

        sync.sync();

        assertThat(docOf(type)).containsEntry("status", "PUBLISHED").containsEntry("version", 2);
        assertThat(auditOf((Long) docOf(type).get("id"))).isEmpty();
    }

    @Test
    void leavesManualDocumentsAloneEvenInTheRunThatArchivesARetiredProtocol() {
        long manual = support.document("tay", AiItSupport.content(1), "PUBLISHED", 1, 1, "INDEXED");
        long type = vaccineType("DOG", "Dại");
        long dose1 = dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE id = ?", dose1);

        sync.sync();

        assertThat(docOf(type)).containsEntry("status", "ARCHIVED");
        assertThat(support.row(manual)).containsEntry("status", "PUBLISHED").containsEntry("updated_by", null);
        assertThat(auditOf(manual)).isEmpty();
    }

    @Test
    void archivingTwiceWritesOneAuditRowOnly() {
        long type = vaccineType("DOG", "Dại");
        long dose1 = dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        long id = (Long) docOf(type).get("id");
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE id = ?", dose1);

        sync.sync();
        sync.sync();

        assertThat(auditOf(id)).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_ARCHIVED");
    }

    @Test
    void theWriterDoesNotArchiveADocumentThatTheManagerArchivedInTheMeantime() {
        long type = vaccineType("DOG", "Dại");
        dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        long id = (Long) docOf(type).get("id");
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED', updated_by = NULL WHERE id = ?", id);

        assertThat(writer.archiveRetired(id)).isFalse();

        assertThat(docOf(type)).containsEntry("status", "ARCHIVED");
        assertThat(auditOf(id)).isEmpty();
    }

    /** ADR-0130 #5, phương án A (Hiến chọn 12/11/2026): hệ thống lưu trữ ({@code updated_by} null) thì hệ thống xuất bản lại. */
    @Test
    void anAutoArchivedDocumentComesBackPublishedWithFreshContentWhenTheProtocolReturns() {
        long type = vaccineType("DOG", "Dại");
        long dose1 = dose("DOG", type, 1, 6, 21, false, true);
        sync.sync();
        long id = (Long) docOf(type).get("id");
        jdbc.update("UPDATE vaccination_protocols SET is_active = false WHERE id = ?", dose1);
        sync.sync();
        jdbc.update("UPDATE vaccination_protocols SET is_active = true, interval_days = 30 WHERE id = ?", dose1);

        sync.sync();

        assertThat(docCount(type)).as("vẫn một tài liệu (chỉ mục duy nhất theo vaccine_type_id)").isEqualTo(1);
        Map<String, Object> doc = docOf(type);
        assertThat(doc).containsEntry("status", "PUBLISHED").containsEntry("version", 2)
                .containsEntry("index_status", "PENDING").containsEntry("updated_by", null);
        assertThat((String) doc.get("content")).as("dựng lại từ phác đồ hiện tại").contains("30 ngày")
                .doesNotContain("21 ngày");
        List<Map<String, Object>> audit = auditOf(id);
        assertThat(audit).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_ARCHIVED", "AI_DOCUMENT_PUBLISHED");
        assertThat(audit).allSatisfy(r -> assertThat(r.get("actor_account_id")).isNull());
        assertThat((String) audit.get(1).get("before_data")).contains("\"status\": \"ARCHIVED\"");
        assertThat((String) audit.get(1).get("after_data")).contains("\"status\": \"PUBLISHED\"");
    }
}
