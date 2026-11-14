package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.ai.AiItSupport;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.ai.job.KnowledgeIndexJob;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;
import com.petcare.module.content.ContentItSupport;

/**
 * ST22 phần đồng bộ bài viết đã xuất bản vào kho tri thức (07 §5.3, BR-AI-30, FSM #13 dòng #1, #4, #5, #8; docs/adr/0134
 * "Đã cài ở T90"). Bài dựng bằng API của chính module content (không SQL) trên Postgres thật; trạng thái tài liệu đã
 * {@code PUBLISHED} (người quản lý đã rà soát) dựng bằng SQL như {@code CatalogProtocolSyncIT}. Era 2004 của
 * {@link ContentItSupport}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ContentItSupport.ContentClockConfig.class, ArticleSyncIT.FakeClients.class})
class ArticleSyncIT extends ContentItSupport {

    private static final String TAG = "ArticleSyncIT";
    private static final String PATH = "/api/articles";

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeClients {
        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }

        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    @Autowired
    private ArticleSync sync;
    @Autowired
    private ArticleWriter writer;
    @Autowired
    private KnowledgeIndexJob job;
    @Autowired
    private ChunkRetriever retriever;
    @Autowired
    private FakeEmbeddingClient embeddings;

    private AiItSupport support;
    private String superManager;
    private long superManagerId;
    private long category;
    private final List<Long> articleIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        embeddings.reset();
        support = new AiItSupport(jdbc, TAG);
        superManagerId = staff("SUPER_MANAGER", null);
        superManager = token(superManagerId);
        category = newCategory("sync", false);
    }

    @AfterEach
    void cleanAi() {
        for (long id : articleIds) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'ai_documents' AND entity_id IN"
                    + " (SELECT id FROM ai_documents WHERE external_id = ?)", "ARTICLE-" + id);
        }
        support.cleanUp();
        articleIds.clear();
    }

    // ---------------------------------------------------------------- dựng dữ liệu bằng API của content

    private long draftArticle(String name, String html) {
        Map<String, Object> body = Map.of("categoryId", category, "title", TAG + " " + name, "slug", "art-"
                + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8), "content", html, "authorDisplayName", "Bác sĩ An");
        long id = number(data(call(HttpMethod.POST, PATH, superManager, body), HttpStatus.CREATED).get("articleId"));
        articleIds.add(id);
        return id;
    }

    private long publishedArticle(String name, String html) {
        long id = draftArticle(name, html);
        post(id, "publish");
        return id;
    }

    private void post(long id, String action) {
        data(call(HttpMethod.POST, PATH + "/" + id + "/" + action, superManager, null), HttpStatus.OK);
    }

    private void edit(long id, Map<String, Object> changes) {
        data(call(HttpMethod.PATCH, PATH + "/" + id, superManager, changes), HttpStatus.OK);
    }

    // ---------------------------------------------------------------- đọc / dựng trạng thái tài liệu

    private Map<String, Object> docOf(long articleId) {
        return jdbc.queryForMap("SELECT * FROM ai_documents WHERE external_id = ?", "ARTICLE-" + articleId);
    }

    private long docCount(long articleId) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_documents WHERE external_id = ?", Long.class, "ARTICLE-" + articleId);
    }

    private List<Map<String, Object>> auditOf(long docId) {
        return jdbc.queryForList("""
                SELECT action, actor_account_id, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'ai_documents' AND entity_id = ? ORDER BY id""", docId);
    }

    /** Người quản lý đã rà soát và xuất bản tài liệu của bài: chatbot đang phục vụ một đoạn {@code text}. */
    private long servedDoc(long articleId, String text) {
        long id = (Long) docOf(articleId).get("id");
        jdbc.update("""
                UPDATE ai_documents SET status = 'PUBLISHED', reviewer_name = 'BS. Thử (IT)', reviewed_on = DATE '2004-03-01',
                    updated_by = ?, indexed_version = version, index_status = 'INDEXED' WHERE id = ?""", superManagerId, id);
        support.chunk(id, ((Number) docOf(articleId).get("version")).intValue(), 0, text);
        return id;
    }

    private List<Long> servedBy(String text) {
        return retriever.nearest(FakeEmbeddingClient.vectorOf(text), 50).stream().map(RetrievedChunk::documentId).toList();
    }

    // ================================================================ bài mới

    @Test
    void aPublishedArticleBecomesOneDraftDocumentWithItsSourceNoteAndNoReviewer() {
        long id = publishedArticle("mới", "<h2>Tắm</h2><p>Tắm cho mèo <b>hai tuần</b> một lần.</p>");

        sync.sync();

        Map<String, Object> doc = docOf(id);
        assertThat(doc).containsEntry("source_type", "MANUAL").containsEntry("status", "DRAFT")
                .containsEntry("topic", "CARE").containsEntry("species", "ALL").containsEntry("version", 1)
                .containsEntry("title", TAG + " mới").containsEntry("reviewer_name", null)
                .containsEntry("reviewed_on", null).containsEntry("updated_by", null).containsEntry("vaccine_type_id", null)
                .containsEntry("source_note", "Bài viết #" + id + " của Bác sĩ An, xuất bản 10/03/2004");
        assertThat((String) doc.get("content")).isEqualTo("## Tắm\n\nTắm cho mèo hai tuần một lần.");
        assertThat((String) doc.get("content_hash")).hasSize(64);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_chunks WHERE document_id = ?", Long.class, doc.get("id")))
                .as("nháp không có đoạn").isZero();
        List<Map<String, Object>> audit = auditOf((Long) doc.get("id"));
        assertThat(audit).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_CREATED");
        assertThat(audit.get(0).get("actor_account_id")).as("actor hệ thống").isNull();
    }

    @Test
    void anUnpublishedArticleCreatesNothing() {
        long draft = draftArticle("nháp", "<p>Chưa xuất bản</p>");
        long hidden = publishedArticle("ẩn", "<p>Đã ẩn</p>");
        post(hidden, "hide");

        sync.sync();

        assertThat(docCount(draft)).isZero();
        assertThat(docCount(hidden)).isZero();
    }

    @Test
    void runningTheSyncAgainCreatesNoDuplicateAndWritesNoExtraAudit() {
        long id = publishedArticle("lặp", "<p>Nội dung</p>");
        sync.sync();
        long docId = (Long) docOf(id).get("id");

        int second = sync.sync();

        assertThat(second).isZero();
        assertThat(docCount(id)).isEqualTo(1);
        assertThat(docOf(id)).containsEntry("version", 1);
        assertThat(auditOf(docId)).hasSize(1);
    }

    // ================================================================ bài đổi nội dung

    @Test
    void aDraftDocumentFollowsTheArticleAndBumpsTheVersion() {
        long id = publishedArticle("đổi", "<p>Bản một</p>");
        sync.sync();
        long docId = (Long) docOf(id).get("id");
        edit(id, Map.of("content", "<p>Bản hai</p>"));

        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "DRAFT").containsEntry("version", 2).containsEntry("content", "Bản hai");
        assertThat(auditOf(docId)).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_CREATED", "AI_DOCUMENT_UPDATED");
    }

    @Test
    void aManagersEditOfTheDraftIsNotOverwrittenWhileTheArticleIsUnchanged() {
        long id = publishedArticle("chỉnh tay", "<p>Gốc</p>");
        sync.sync();
        jdbc.update("UPDATE ai_documents SET content = 'Bản đã chỉnh tay', updated_by = ? WHERE external_id = ?", superManagerId,
                "ARTICLE-" + id);

        int changed = sync.sync();

        assertThat(changed).isZero();
        assertThat(docOf(id)).containsEntry("content", "Bản đã chỉnh tay").containsEntry("version", 1);
    }

    @Test
    void aPublishedDocumentGoesBackToDraftAndLosesItsReviewerWhenTheArticleChanges() {
        long id = publishedArticle("đã duyệt", "<p>Bản cũ đã duyệt</p>");
        sync.sync();
        long docId = servedDoc(id, "Bản cũ đã duyệt");
        assertThat(servedBy("Bản cũ đã duyệt")).contains(docId);
        edit(id, Map.of("content", "<p>Bản mới chưa ai duyệt</p>"));

        sync.sync();

        Map<String, Object> doc = docOf(id);
        assertThat(doc).containsEntry("status", "DRAFT").containsEntry("version", 2).containsEntry("reviewer_name", null)
                .containsEntry("reviewed_on", null).containsEntry("updated_by", null)
                .containsEntry("content", "Bản mới chưa ai duyệt");
        assertThat(servedBy("Bản cũ đã duyệt")).as("hết phục vụ ngay khi commit").doesNotContain(docId);
        List<Map<String, Object>> audit = auditOf(docId);
        assertThat(audit.get(audit.size() - 1)).containsEntry("action", "AI_DOCUMENT_UPDATED").containsEntry("actor_account_id", null);
        assertThat((String) audit.get(audit.size() - 1).get("before_data")).contains("\"status\": \"PUBLISHED\"");
        assertThat((String) audit.get(audit.size() - 1).get("after_data")).contains("\"status\": \"DRAFT\"");
    }

    @Test
    void aNewTitleAloneAlsoCountsAsAChange() {
        long id = publishedArticle("tiêu đề", "<p>Giữ nguyên</p>");
        sync.sync();
        edit(id, Map.of("title", TAG + " tiêu đề mới"));

        sync.sync();

        assertThat(docOf(id)).containsEntry("title", TAG + " tiêu đề mới").containsEntry("version", 2);
    }

    // ================================================================ bài bị gỡ và xuất bản lại

    @Test
    void anArticleThatIsNoLongerPublishedArchivesItsPublishedDocumentAndTheChatbotStopsServingIt() {
        long id = publishedArticle("gỡ", "<p>Sắp gỡ</p>");
        sync.sync();
        long docId = servedDoc(id, "Sắp gỡ");
        post(id, "hide");

        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "ARCHIVED").containsEntry("updated_by", null);
        assertThat(servedBy("Sắp gỡ")).doesNotContain(docId);
        List<Map<String, Object>> audit = auditOf(docId);
        assertThat(audit.get(audit.size() - 1)).containsEntry("action", "AI_DOCUMENT_ARCHIVED").containsEntry("actor_account_id", null);
        sync.sync();
        assertThat(auditOf(docId)).as("lưu trữ hai lần chỉ ghi một audit").hasSize(audit.size());
    }

    /** Khóa dòng rồi kiểm lại trạng thái: người quản lý vừa lưu trữ thì ST22 không đụng, không ghi đè {@code updated_by}. */
    @Test
    void theWriterNeverTouchesADocumentTheManagerArchivedInTheMeantime() {
        long id = publishedArticle("tranh chấp", "<p>Tranh chấp</p>");
        sync.sync();
        long docId = servedDoc(id, "Tranh chấp");
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED', updated_by = ? WHERE id = ?", superManagerId, docId);

        assertThat(writer.archiveRemoved(docId)).isFalse();

        assertThat(docOf(id)).containsEntry("status", "ARCHIVED").containsEntry("updated_by", superManagerId);
        assertThat(auditOf(docId)).extracting(r -> r.get("action")).containsExactly("AI_DOCUMENT_CREATED");
    }

    @Test
    void aDraftDocumentOfAHiddenArticleIsLeftAlone() {
        long id = publishedArticle("nháp bị gỡ", "<p>Nháp</p>");
        sync.sync();
        long docId = (Long) docOf(id).get("id");
        post(id, "hide");

        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "DRAFT");
        assertThat(auditOf(docId)).hasSize(1);
    }

    @Test
    void aSystemArchivedDocumentComesBackPublishedWhenTheSameArticleIsPublishedAgain() {
        long id = publishedArticle("lại", "<p>Vẫn như cũ</p>");
        sync.sync();
        long docId = servedDoc(id, "Vẫn như cũ");
        post(id, "hide");
        sync.sync();
        post(id, "publish");

        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "PUBLISHED").containsEntry("version", 1)
                .containsEntry("index_status", "PENDING").containsEntry("updated_by", null);
        List<Map<String, Object>> audit = auditOf(docId);
        assertThat(audit.get(audit.size() - 1)).containsEntry("action", "AI_DOCUMENT_PUBLISHED").containsEntry("actor_account_id", null);
    }

    @Test
    void aDocumentTheManagerArchivedNeverComesBack() {
        long id = publishedArticle("tay", "<p>Người quản lý lưu trữ</p>");
        sync.sync();
        servedDoc(id, "Người quản lý lưu trữ");
        jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED', updated_by = ? WHERE external_id = ?", superManagerId,
                "ARTICLE-" + id);

        sync.sync();
        post(id, "hide");
        sync.sync();
        post(id, "publish");
        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "ARCHIVED").containsEntry("updated_by", superManagerId);
    }

    /** Không có cạnh ARCHIVED → DRAFT: nội dung mới chưa duyệt không được đi thẳng lên PUBLISHED, nên tài liệu ở nguyên. */
    @Test
    void anArchivedDocumentStaysArchivedWhenTheArticleReturnsWithOtherContent() {
        long id = publishedArticle("lệch", "<p>Bản đã duyệt</p>");
        sync.sync();
        servedDoc(id, "Bản đã duyệt");
        post(id, "hide");
        sync.sync();
        edit(id, Map.of("content", "<p>Bản mới chưa duyệt</p>"));
        post(id, "publish");

        sync.sync();

        assertThat(docOf(id)).containsEntry("status", "ARCHIVED").containsEntry("content", "Bản đã duyệt")
                .containsEntry("version", 1);
    }

    // ================================================================ ST22

    /** Job ST22 chạy bước bài viết cùng chu kỳ với phác đồ (đồng hồ nhảy qua [CFG] ai.index.catalog_sync_minutes). */
    @Test
    void theSt22JobSyncsArticlesInTheSameCycleAsTheCatalog() {
        long id = publishedArticle("job", "<p>Do job tạo</p>");
        clock.advance(Duration.ofDays(1));

        job.run();

        assertThat(docOf(id)).containsEntry("status", "DRAFT");
    }

    // ================================================================ giới hạn

    @Test
    void anArticleOverTheContentLimitIsSkippedAndNeverTruncated() {
        long id = publishedArticle("dài", "<p>" + "chữ ".repeat(6_000) + "</p>");

        sync.sync();

        assertThat(docCount(id)).isZero();
    }
}
