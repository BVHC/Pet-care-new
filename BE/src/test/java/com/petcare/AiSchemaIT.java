package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Schema AI của V34 trên pgvector thật (docs/adr/0110, T55a; đặc tả docs/07-ai-subsystem.md §8, §5.1, §13). Mọi kỳ vọng
 * là bản sao độc lập chép từ 07, không đọc lại từ V34. Chứng minh: P1 extension {@code vector} có mặt và cột
 * {@code embedding} cố định 768 chiều · P2 hai lớp kiểm soát của BR-AI-14 do CSDL giữ (thiếu nguồn, thiếu người rà soát
 * hoặc ngày rà soát thì không {@code PUBLISHED}) · P3 phác đồ ↔ loại vaccine, enum, độ dài, mặc định · P4 {@code ai_chat_logs}
 * giữ BR-AI-24 (không tài khoản / IP; ba kết quả cá nhân không có nội dung) · P5 {@code ai_item_profiles} · P6 truy hồi cosine
 * {@code <=>} với lọc {@code status = 'PUBLISHED' AND doc_version = indexed_version} đúng như câu §5.1, kể cả hoán đổi
 * phiên bản trong một transaction (BR-AI-16). Mỗi test tự dọn dữ liệu của mình (tiền tố {@code AiSchemaIT}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AiSchemaIT {

    private static final String TAG = "AiSchemaIT";
    private static final int DIMENSIONS = 768;
    private static final LocalDate REVIEWED = LocalDate.of(2026, 11, 5);

    /** Câu truy hồi của 07 §5.1; chỉ thêm LIMIT cố định. */
    private static final String RETRIEVAL = """
            SELECT c.id, c.document_id, c.text, 1 - (c.embedding <=> CAST(? AS vector)) AS similarity
            FROM ai_chunks c JOIN ai_documents d ON d.id = c.document_id
            WHERE d.status = 'PUBLISHED' AND c.doc_version = d.indexed_version
            ORDER BY c.embedding <=> CAST(? AS vector)
            LIMIT 10
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ai_item_profiles WHERE service_id IN (SELECT id FROM services WHERE name LIKE ?)"
                + " OR product_id IN (SELECT id FROM products WHERE name LIKE ?)", TAG + "%", TAG + "%");
        jdbc.update("DELETE FROM ai_chunks WHERE document_id IN (SELECT id FROM ai_documents WHERE title LIKE ?)",
                TAG + "%");
        jdbc.update("DELETE FROM ai_documents WHERE title LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM ai_chat_logs WHERE llm_model = ?", TAG);
        jdbc.update("DELETE FROM services WHERE name LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM products WHERE name LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM product_categories WHERE name LIKE ?", TAG + "%");
        jdbc.update("DELETE FROM vaccine_types WHERE name LIKE ?", TAG + "%");
    }

    // ---------------------------------------------------------------- P1: pgvector

    @Test
    void vectorExtensionIsInstalledAndEmbeddingIsExactly768Dimensions() {
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class))
                .containsExactly("vector");
        assertThat(jdbc.queryForObject("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = 'ai_chunks'::regclass AND attname = 'embedding' AND NOT attisdropped
                """, String.class)).isEqualTo("vector(768)");
        assertThat(jdbc.queryForObject("""
                SELECT attnotnull FROM pg_attribute WHERE attrelid = 'ai_chunks'::regclass AND attname = 'embedding'
                """, Boolean.class)).isTrue();
    }

    @Test
    void chunkWithAnotherNumberOfDimensionsIsRejected() {
        long document = manualDocument("DRAFT", null, null);

        assertThatThrownBy(() -> chunk(document, 1, 1, "[1,2,3]", "ngắn"))
                .hasMessageContaining("expected 768 dimensions, not 3");
        String tooLong = "[" + IntStream.range(0, DIMENSIONS + 1).mapToObj(i -> "1").collect(Collectors.joining(",")) + "]";
        assertThatThrownBy(() -> chunk(document, 1, 1, tooLong, "dài"))
                .hasMessageContaining("expected 768 dimensions, not 769");
    }

    // ---------------------------------------------------------------- P2, P3: ai_documents

    /** BR-AI-14 lớp 2: tài liệu soạn tay chưa có CẢ người rà soát lẫn ngày rà soát thì không được PUBLISHED. */
    @ParameterizedTest(name = "reviewer={0}, reviewedOn={1} → bị chặn")
    @CsvSource(value = {"NULL, NULL", "BS. Minh, NULL", "NULL, 2026-11-05"}, nullValues = "NULL")
    void manualDocumentCannotBePublishedWithoutBothReviewerAndDate(String reviewer, LocalDate reviewedOn) {
        assertThatThrownBy(() -> document("MANUAL", null, "PUBLISHED", "Nguồn: hướng dẫn công khai", reviewer, reviewedOn))
                .hasMessageContaining("ck_ai_documents_manual_published_reviewed");
    }

    @Test
    void manualDocumentIsPublishedOnlyWithReviewerAndDateAndOtherStatusesNeedNone() {
        assertThat(manualDocument("PUBLISHED", "BS. Minh", REVIEWED)).isPositive();
        assertThat(manualDocument("DRAFT", null, null)).as("DRAFT chưa cần người rà soát").isPositive();
        assertThat(manualDocument("ARCHIVED", null, null)).as("ARCHIVED không cần").isPositive();
    }

    /** BR-AI-14 lớp 1: tài liệu soạn tay luôn có nguồn, dù ở trạng thái nào. */
    @Test
    void manualDocumentAlwaysNeedsASourceNote() {
        assertThatThrownBy(() -> document("MANUAL", null, "DRAFT", null, null, null))
                .hasMessageContaining("ck_ai_documents_manual_has_source");
    }

    @Test
    void catalogProtocolIsTiedToExactlyOneVaccineTypeAndNeedsNoReviewer() {
        long vaccine = vaccineType();
        assertThatThrownBy(() -> document("CATALOG_PROTOCOL", null, "DRAFT", null, null, null))
                .as("phác đồ thiếu loại vaccine").hasMessageContaining("ck_ai_documents_vaccine_type_tied");
        assertThatThrownBy(() -> document("MANUAL", vaccine, "DRAFT", "Nguồn", null, null))
                .as("tài liệu soạn tay mang loại vaccine").hasMessageContaining("ck_ai_documents_vaccine_type_tied");

        assertThat(document("CATALOG_PROTOCOL", vaccine, "PUBLISHED", null, null, null))
                .as("phác đồ từ danh mục PUBLISHED không cần rà soát tay (BR-AI-14)").isPositive();
        assertThatThrownBy(() -> document("CATALOG_PROTOCOL", vaccine, "DRAFT", null, null, null))
                .as("mỗi loại vaccine một tài liệu").hasMessageContaining("ux_ai_documents_vaccine_type");
    }

    @Test
    void contentIsLimitedTo20000Characters() {
        assertThat(jdbc.update("""
                INSERT INTO ai_documents (source_type, title, topic, species, content, source_note, status)
                VALUES ('MANUAL', ?, 'CARE', 'ALL', ?, 'Nguồn', 'DRAFT')""", TAG + " đủ", "a".repeat(20000))).isOne();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_documents (source_type, title, topic, species, content, source_note, status)
                VALUES ('MANUAL', ?, 'CARE', 'ALL', ?, 'Nguồn', 'DRAFT')""", TAG + " dài", "a".repeat(20001)))
                .hasMessageContaining("ck_ai_documents_content_length");
    }

    @ParameterizedTest(name = "{0} = {1} → bị chặn")
    @CsvSource({
            "source_type,  WEB", "topic, PRICE", "species, BIRD", "status, HIDDEN", "index_status, DONE"})
    void enumColumnsRejectUnknownValues(String column, String value) {
        Map<String, Object> row = new LinkedHashMap<>(Map.of("source_type", "MANUAL", "topic", "CARE", "species", "ALL",
                "status", "DRAFT", "index_status", "PENDING"));
        row.put(column, value);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ai_documents (source_type, title, topic, species, content, source_note, status, index_status)
                VALUES (?, ?, ?, ?, 'x', 'Nguồn', ?, ?)""", row.get("source_type"), TAG + " " + UUID.randomUUID(),
                row.get("topic"), row.get("species"), row.get("status"), row.get("index_status")))
                .hasMessageContaining("ck_ai_documents_" + column);
    }

    @Test
    void indexingColumnsHaveSafeDefaultsAndTimestamps() {
        long id = manualDocument("DRAFT", null, null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, indexed_version, index_status, published_at, created_at, updated_at FROM ai_documents WHERE id = ?",
                id);
        assertThat(row).containsEntry("version", 1).containsEntry("indexed_version", 0)
                .containsEntry("index_status", "PENDING").containsEntry("published_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    // ---------------------------------------------------------------- ai_chunks

    @Test
    void chunkIsUniquePerDocumentVersionAndOrdinalAndRestrictsDocumentDelete() {
        long document = manualDocument("DRAFT", null, null);
        chunk(document, 1, 1, vector(1, 0), "đoạn 1");
        chunk(document, 2, 1, vector(1, 0), "cùng thứ tự nhưng phiên bản khác thì được");

        assertThatThrownBy(() -> chunk(document, 1, 1, vector(0, 1), "trùng"))
                .hasMessageContaining("uq_ai_chunks_document_version_ordinal");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ai_documents WHERE id = ?", document))
                .as("FK ON DELETE RESTRICT").hasMessageContaining("ai_chunks_document_id_fkey");
    }

    // ---------------------------------------------------------------- P4: ai_chat_logs

    @Test
    void chatLogKeepsNoAccountOrIpAndIsInsertOnly() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_chat_logs'""", String.class);

        assertThat(columns).containsExactlyInAnyOrder("id", "result", "error_kind", "question", "answer",
                "top_similarity", "source_document_ids", "llm_model", "input_tokens", "output_tokens",
                "thought_tokens", "latency_ms", "created_at");
    }

    @Test
    void errorKindExistsExactlyForUnavailableResults() {
        assertThat(chatLog("UNAVAILABLE", "TIMEOUT", null, null)).isOne();
        assertThat(chatLog("ANSWERED", null, "Câu hỏi", "Trả lời")).isOne();
        assertThatThrownBy(() -> chatLog("UNAVAILABLE", null, null, null))
                .hasMessageContaining("ck_ai_chat_logs_error_kind_tied");
        assertThatThrownBy(() -> chatLog("ANSWERED", "TIMEOUT", "Câu hỏi", "Trả lời"))
                .hasMessageContaining("ck_ai_chat_logs_error_kind_tied");
        assertThatThrownBy(() -> chatLog("UNAVAILABLE", "OUT_OF_QUOTA", null, null))
                .hasMessageContaining("ck_ai_chat_logs_error_kind");
        assertThatThrownBy(() -> chatLog("MAYBE", null, null, null)).hasMessageContaining("ck_ai_chat_logs_result");
    }

    /** BR-AI-24: kết quả PERSONAL, NEED_PET_CHOICE, NEED_LOGIN chỉ ghi kết quả và độ trễ. */
    @ParameterizedTest
    @CsvSource({"PERSONAL", "NEED_PET_CHOICE", "NEED_LOGIN"})
    void personalResultsNeverStoreQuestionOrAnswer(String result) {
        assertThat(chatLog(result, null, null, null)).isOne();
        assertThatThrownBy(() -> chatLog(result, null, "Lịch hẹn của Mochi?", null))
                .hasMessageContaining("ck_ai_chat_logs_no_content_for_personal");
        assertThatThrownBy(() -> chatLog(result, null, null, "Mochi có lịch lúc 9 giờ"))
                .hasMessageContaining("ck_ai_chat_logs_no_content_for_personal");
    }

    // ---------------------------------------------------------------- P5: ai_item_profiles

    @Test
    void itemProfileTargetsExactlyOneOfServiceOrProductAndEachOnlyOnce() {
        long service = service();
        long product = product();

        assertThat(jdbc.update("INSERT INTO ai_item_profiles (service_id) VALUES (?)", service)).isOne();
        assertThat(jdbc.update("INSERT INTO ai_item_profiles (product_id) VALUES (?)", product)).isOne();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_item_profiles (service_id, product_id) VALUES (?, ?)",
                service(), product())).hasMessageContaining("ck_ai_item_profiles_one_target");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_item_profiles (species) VALUES ('DOG')"))
                .hasMessageContaining("ck_ai_item_profiles_one_target");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_item_profiles (service_id) VALUES (?)", service))
                .hasMessageContaining("ux_ai_item_profiles_service");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_item_profiles (product_id) VALUES (?)", product))
                .hasMessageContaining("ux_ai_item_profiles_product");
    }

    @Test
    void itemProfileRangesSpeciesAndIntervalAreChecked() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ai_item_profiles (service_id, min_age_weeks, max_age_weeks) VALUES (?, 20, 10)", service()))
                .hasMessageContaining("ck_ai_item_profiles_age_range");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ai_item_profiles (service_id, min_weight_kg, max_weight_kg) VALUES (?, 12.5, 3.0)",
                service())).hasMessageContaining("ck_ai_item_profiles_weight_range");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ai_item_profiles (service_id, repeat_interval_days) VALUES (?, 0)", service()))
                .hasMessageContaining("ck_ai_item_profiles_repeat_interval");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_item_profiles (service_id, species) VALUES (?, 'ALL')",
                service())).as("NULL = mọi loài, không có giá trị ALL").hasMessageContaining("ck_ai_item_profiles_species");

        assertThat(jdbc.update("""
                INSERT INTO ai_item_profiles (service_id, species, min_age_weeks, max_age_weeks, min_weight_kg,
                    max_weight_kg, breed_keywords, repeat_interval_days)
                VALUES (?, 'DOG', 8, 520, 2.5, 40.00, ARRAY['poodle', 'phoc soc'], 30)""", service())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO ai_item_profiles (product_id, min_age_weeks, max_age_weeks) VALUES (?, 12, 12)""", product()))
                .as("biên bằng nhau hợp lệ; NULL = không giới hạn").isOne();
    }

    // ---------------------------------------------------------------- P6: truy hồi cosine

    /**
     * 07 §5.1 / §13: chỉ đoạn của tài liệu PUBLISHED và đúng phiên bản đang phục vụ ({@code doc_version = indexed_version})
     * mới được trả về, dù đoạn bị loại giống câu hỏi hơn; xếp theo cosine giảm dần.
     */
    @Test
    void cosineRetrievalReturnsOnlyPublishedCurrentVersionChunksRankedBySimilarity() {
        long served = manualDocument("PUBLISHED", "BS. Minh", REVIEWED);
        setIndexed(served, 1, 1);
        long a1 = chunk(served, 1, 1, vector(1, 0), "a1 khớp hoàn toàn");
        long a2 = chunk(served, 1, 2, vector(0.8, 0.6), "a2 khớp 0,8");
        long a3 = chunk(served, 1, 3, vector(0, 1), "a3 vuông góc");

        long upgraded = manualDocument("PUBLISHED", "BS. Minh", REVIEWED);   // đã sửa lên v2, chưa nạp xong
        setIndexed(upgraded, 2, 1);
        long b1 = chunk(upgraded, 1, 1, vector(0.9, 0.1), "b1 phiên bản đang phục vụ");
        long b2 = chunk(upgraded, 2, 1, vector(1, 0), "b2 phiên bản chưa phục vụ (giống hệt câu hỏi)");

        long archived = manualDocument("ARCHIVED", null, null);
        setIndexed(archived, 1, 1);
        long c1 = chunk(archived, 1, 1, vector(1, 0), "c1 đã lưu trữ");
        long draft = manualDocument("DRAFT", null, null);
        setIndexed(draft, 1, 1);
        long d1 = chunk(draft, 1, 1, vector(1, 0), "d1 bản nháp");

        List<Map<String, Object>> hits = retrieve(vector(1, 0));

        assertThat(hits).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(a1, b1, a2, a3)
                .doesNotContain(b2, c1, d1);
        assertThat(((Number) hits.get(0).get("similarity")).doubleValue()).isCloseTo(1.0, within(1e-6));
        assertThat(((Number) hits.get(1).get("similarity")).doubleValue()).isCloseTo(0.9 / Math.sqrt(0.82), within(1e-6));
        assertThat(((Number) hits.get(2).get("similarity")).doubleValue()).isCloseTo(0.8, within(1e-6));
        assertThat(((Number) hits.get(3).get("similarity")).doubleValue()).isCloseTo(0.0, within(1e-6));
    }

    /** 07 §13: lưu trữ có hiệu lực ngay; đổi {@code indexed_version} khi chưa có đoạn mới làm tài liệu biến mất. */
    @Test
    void archivingTakesEffectAtOnceAndPointingAtAMissingVersionHidesTheDocument() {
        long document = manualDocument("PUBLISHED", "BS. Minh", REVIEWED);
        setIndexed(document, 1, 1);
        long chunk = chunk(document, 1, 1, vector(1, 0), "đoạn phục vụ");
        assertThat(ids(retrieve(vector(1, 0)))).containsExactly(chunk);

        jdbc.update("UPDATE ai_documents SET version = 2 WHERE id = ?", document);
        assertThat(ids(retrieve(vector(1, 0)))).as("sửa nội dung nhưng chưa nạp: đoạn cũ vẫn phục vụ")
                .containsExactly(chunk);

        jdbc.update("UPDATE ai_documents SET indexed_version = 2 WHERE id = ?", document);
        assertThat(retrieve(vector(1, 0))).as("trỏ tới phiên bản chưa có đoạn: biến mất (nên phải làm trong một transaction)")
                .isEmpty();

        jdbc.update("UPDATE ai_documents SET indexed_version = 1 WHERE id = ?", document);
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED' WHERE id = ?", document);
        assertThat(retrieve(vector(1, 0))).as("ARCHIVED có hiệu lực ngay, không nạp lại gì").isEmpty();
    }

    /** BR-AI-16: ghi đoạn mới và đổi {@code indexed_version} trong cùng một transaction nên người đọc không thấy khoảng trống. */
    @Test
    void swappingToANewVersionInOneTransactionNeverShowsAGapToConcurrentReaders() throws Exception {
        long document = manualDocument("PUBLISHED", "BS. Minh", REVIEWED);
        setIndexed(document, 1, 1);
        long old = chunk(document, 1, 1, vector(1, 0), "đoạn phiên bản 1");
        jdbc.update("UPDATE ai_documents SET version = 2 WHERE id = ?", document);

        long[] fresh = new long[1];
        List<Long> duringTx = tx.execute(status -> {
            fresh[0] = chunk(document, 2, 1, vector(1, 0), "đoạn phiên bản 2");
            jdbc.update("UPDATE ai_documents SET indexed_version = 2 WHERE id = ?", document);
            // Người đọc khác (kết nối khác, READ COMMITTED) chưa thấy gì thay đổi trước khi COMMIT
            return CompletableFuture.supplyAsync(() -> ids(retrieve(vector(1, 0)))).orTimeout(10, TimeUnit.SECONDS).join();
        });

        assertThat(duringTx).as("trước COMMIT vẫn phục vụ phiên bản 1").containsExactly(old);
        assertThat(ids(retrieve(vector(1, 0)))).as("sau COMMIT phục vụ phiên bản 2").containsExactly(fresh[0]);
    }

    // ---------------------------------------------------------------- helpers

    private List<Map<String, Object>> retrieve(String query) {
        return jdbc.queryForList(RETRIEVAL, query, query);
    }

    private static List<Long> ids(List<Map<String, Object>> hits) {
        return hits.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    /** Vector 768 chiều: {@code head} là các thành phần đầu, phần còn lại bằng 0. */
    private static String vector(double... head) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < DIMENSIONS; i++) {
            text.append(i == 0 ? "" : ",").append(i < head.length ? head[i] : 0.0);
        }
        return text.append(']').toString();
    }

    private long manualDocument(String status, String reviewer, LocalDate reviewedOn) {
        return document("MANUAL", null, status, "Nguồn: hướng dẫn công khai của nhà sản xuất", reviewer, reviewedOn);
    }

    private long document(String sourceType, Long vaccineTypeId, String status, String sourceNote, String reviewer,
            LocalDate reviewedOn) {
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (source_type, vaccine_type_id, title, topic, species, content, source_note,
                    reviewer_name, reviewed_on, status)
                VALUES (?, ?, ?, 'CARE', 'ALL', 'Nội dung thử, không phải tri thức thật', ?, ?, ?, ?) RETURNING id""",
                Long.class, sourceType, vaccineTypeId, TAG + " " + UUID.randomUUID(), sourceNote, reviewer, reviewedOn,
                status);
    }

    private void setIndexed(long document, int version, int indexedVersion) {
        jdbc.update("UPDATE ai_documents SET version = ?, indexed_version = ?, index_status = 'INDEXED' WHERE id = ?",
                version, indexedVersion, document);
    }

    private long chunk(long document, int docVersion, int ordinal, String embedding, String text) {
        return jdbc.queryForObject("""
                INSERT INTO ai_chunks (document_id, doc_version, ordinal, text, embedding, embedding_model)
                VALUES (?, ?, ?, ?, CAST(? AS vector), 'gemini-embedding-001') RETURNING id""",
                Long.class, document, docVersion, ordinal, text, embedding);
    }

    private int chatLog(String result, String errorKind, String question, String answer) {
        return jdbc.update("""
                INSERT INTO ai_chat_logs (result, error_kind, question, answer, llm_model, latency_ms)
                VALUES (?, ?, ?, ?, ?, 12)""", result, errorKind, question, answer, TAG);
    }

    private long vaccineType() {
        return jdbc.queryForObject("INSERT INTO vaccine_types (name, species) VALUES (?, 'DOG') RETURNING id",
                Long.class, TAG + " " + UUID.randomUUID());
    }

    private long service() {
        return jdbc.queryForObject("""
                INSERT INTO services (name, service_group, price) VALUES (?, 'GROOMING', 100000) RETURNING id""",
                Long.class, TAG + " " + UUID.randomUUID());
    }

    private long product() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, TAG + " " + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price)
                VALUES (?, ?, ?, 'GOODS', false, 'gói', 1000) RETURNING id""", Long.class, category,
                "AI-" + UUID.randomUUID().toString().substring(0, 8), TAG + " " + UUID.randomUUID());
    }
}
