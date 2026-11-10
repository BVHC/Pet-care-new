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

/**
 * ST22 phần đồng bộ tài liệu {@code CATALOG_PROTOCOL} với phác đồ tiêm đang hoạt động (07 §5.3 bước 2, BR-AI-13, FSM #13
 * dòng #7; docs/adr/0130): mỗi loại vaccine một tài liệu {@code PUBLISHED}, mã băm đổi thì dựng lại nội dung và tăng
 * {@code version}, tài liệu đã lưu trữ thì không đụng. Dùng chung context với {@link KnowledgeIndexerIT}. Dữ liệu danh
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
    private FakeEmbeddingClient embeddings;
    @Autowired
    private JdbcTemplate jdbc;

    private AiItSupport support;
    private final List<Long> types = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new AiItSupport(jdbc, TAG);
        embeddings.reset();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_reject_catalog ON ai_documents");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_reject_catalog()");
        support.deleteCatalogDocuments();
        types.forEach(id -> {
            jdbc.update("DELETE FROM vaccination_protocols WHERE vaccine_type_id = ?", id);
            jdbc.update("DELETE FROM vaccine_types WHERE id = ?", id);
        });
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
                .containsEntry("indexed_version", 1).containsEntry("species", "CAT");
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
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED' WHERE vaccine_type_id = ?", type);
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
}
