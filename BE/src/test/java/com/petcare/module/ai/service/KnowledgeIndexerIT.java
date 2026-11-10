package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.ai.AiItSupport;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.service.DocumentChunker.Chunk;
import com.petcare.module.ai.service.KnowledgeIndexer.Result;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ST22 trên Postgres + pgvector thật (07 §5.3, BR-AI-15, BR-AI-16, docs/adr/0130): nạp, hoán đổi một transaction, đoạn
 * cũ vẫn phục vụ khi nạp lỗi, bỏ lượt nạp khi phiên bản đổi giữa chừng, thử lại 429, và không giữ kết nối trong lúc gọi
 * nhà cung cấp. Embedding là lớp giả tất định. Clock ở năm 2040 (IT không có job quét không cận dưới: era tương lai).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KnowledgeIndexerIT.TestBeans.class})
class KnowledgeIndexerIT {

    private static final String TAG = "KnowledgeIndexerIT";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2040-06-15T03:00:00Z"));
        }

        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }
    }

    @Autowired
    private KnowledgeIndexer indexer;
    @Autowired
    private AiDocumentRepository docs;
    @Autowired
    private FakeEmbeddingClient embeddings;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private AiItSupport support;

    @BeforeEach
    void setUp() {
        embeddings.reset();
        support = new AiItSupport(jdbc, TAG);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS ai_it_reject_indexed ON ai_documents");
        jdbc.execute("DROP FUNCTION IF EXISTS ai_it_reject_indexed()");
        support.cleanUp();
    }

    private static AiProviderException error(ChatErrorKind kind) {
        return new AiProviderException(kind, "Nhà cung cấp AI trả lỗi " + kind);
    }

    /** Tài liệu đã nạp phiên bản 1 với hai đoạn cũ, rồi được sửa thành phiên bản 2 đang chờ nạp. */
    private long indexedAtVersion1ThenEditedToVersion2() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 1, "INDEXED");
        support.chunk(id, 1, 1, "cũ 1");
        support.chunk(id, 1, 2, "cũ 2");
        jdbc.update("UPDATE ai_documents SET version = 2, content = ?, index_status = 'PENDING' WHERE id = ?",
                AiItSupport.content(3), id);
        return id;
    }

    // ---------------------------------------------------------------- nạp

    @Test
    void indexesAPublishedDocumentIntoChunksAndMarksItIndexed() {
        String content = AiItSupport.content(3);
        long id = support.document("a", content, "PUBLISHED", 1, 0, "PENDING");

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.INDEXED);

        Map<String, Object> row = support.row(id);
        assertThat(row.get("index_status")).isEqualTo("INDEXED");
        assertThat(row.get("indexed_version")).isEqualTo(1);
        assertThat(row.get("index_error")).isNull();
        List<Chunk> expected = DocumentChunker.chunk(support.title("a"), content);
        List<Map<String, Object>> chunks = support.chunks(id);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(c -> c.get("doc_version")).containsOnly(1);
        assertThat(chunks).extracting(c -> c.get("ordinal")).containsExactly(1, 2, 3);
        assertThat(chunks).extracting(c -> c.get("embedding_model")).containsOnly(FakeEmbeddingClient.MODEL);
        assertThat(chunks).extracting(c -> c.get("text")).containsExactlyElementsOf(expected.stream().map(Chunk::text).toList());
        assertThat(chunks).extracting(c -> c.get("heading")).containsExactly("Mục 1", "Mục 2", "Mục 3");
        assertThat(embeddings.batches()).hasSize(1);
        assertThat(embeddings.batches().get(0)).containsExactlyElementsOf(expected.stream().map(Chunk::embedText).toList());
        assertThat(embeddings.batches().get(0).get(0)).startsWith(support.title("a") + "\nMục 1\n");
    }

    @Test
    void retrievalSeesTheNewChunksOnlyAfterIndexing() {
        String content = AiItSupport.content(3);
        long id = support.document("a", content, "PUBLISHED", 1, 0, "PENDING");
        float[] query = FakeEmbeddingClient.vectorOf(DocumentChunker.chunk(support.title("a"), content).get(1).embedText());

        assertThat(support.retrieve(query)).isEmpty();

        indexer.indexDocument(id);

        List<Map<String, Object>> found = support.retrieve(query);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).get("ordinal")).isEqualTo(2);
        assertThat((Double) found.get(0).get("similarity")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void swapReplacesTheChunksOfTheOldVersionWithTheNewOnes() {
        long id = indexedAtVersion1ThenEditedToVersion2();

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.INDEXED);

        assertThat(support.chunks(id)).extracting(c -> c.get("doc_version")).containsExactly(2, 2, 2);
        assertThat(support.chunks(id)).extracting(c -> c.get("text")).doesNotContain("cũ 1", "cũ 2");
        assertThat(support.row(id).get("indexed_version")).isEqualTo(2);
        assertThat(support.row(id).get("index_status")).isEqualTo("INDEXED");
    }

    @Test
    void reindexingTheSameVersionIsIdempotent() {
        long id = support.document("a", AiItSupport.content(3), "PUBLISHED", 1, 0, "PENDING");
        indexer.indexDocument(id);
        jdbc.update("UPDATE ai_documents SET index_status = 'PENDING' WHERE id = ?", id);

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.INDEXED);

        assertThat(support.chunks(id)).extracting(c -> c.get("ordinal")).containsExactly(1, 2, 3);
        assertThat(support.chunks(id)).extracting(c -> c.get("doc_version")).containsOnly(1);
        assertThat(support.row(id).get("index_status")).isEqualTo("INDEXED");
    }

    @Test
    void chunksAreEmbeddedInBatchesOfSixteen() {
        long id = support.document("a", AiItSupport.content(20), "PUBLISHED", 1, 0, "PENDING");

        indexer.indexDocument(id);

        assertThat(embeddings.batches()).extracting(List::size).containsExactly(16, 4);
        assertThat(support.chunkCount(id)).isEqualTo(20);
    }

    // ---------------------------------------------------------------- nạp lỗi: đoạn cũ vẫn phục vụ

    @Test
    void embeddingFailureMarksTheDocumentFailedAndTheOldChunksKeepServing() {
        long id = indexedAtVersion1ThenEditedToVersion2();
        embeddings.failAlways(error(ChatErrorKind.PROVIDER_ERROR));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.FAILED);

        Map<String, Object> row = support.row(id);
        assertThat(row.get("index_status")).isEqualTo("FAILED");
        assertThat((String) row.get("index_error")).contains("PROVIDER_ERROR");
        assertThat(row.get("indexed_version")).isEqualTo(1);
        assertThat(support.chunks(id)).extracting(c -> c.get("text")).containsExactly("cũ 1", "cũ 2");
        assertThat(support.retrieve(FakeEmbeddingClient.vectorOf("cũ 1"))).hasSize(2);
    }

    @Test
    void theStoredErrorIsTruncatedToTheColumnLength() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.failAlways(new AiProviderException(ChatErrorKind.PROVIDER_ERROR, "x".repeat(1000)));

        indexer.indexDocument(id);

        assertThat((String) support.row(id).get("index_error")).hasSize(300);
    }

    @Test
    void aRateLimitIsRetriedAndTheDocumentIsStillIndexed() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.failNext(2, error(ChatErrorKind.PROVIDER_RATE_LIMIT));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.INDEXED);

        assertThat(embeddings.batches()).hasSize(3);
    }

    @Test
    void aRateLimitThatKeepsComingBackStopsAtMaxAttemptsAndFails() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.failAlways(error(ChatErrorKind.PROVIDER_RATE_LIMIT));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.FAILED);

        assertThat(embeddings.batches()).hasSize(3);
        assertThat((String) support.row(id).get("index_error")).contains("PROVIDER_RATE_LIMIT");
    }

    @ParameterizedTest
    @EnumSource(value = ChatErrorKind.class, names = {"TIMEOUT", "PROVIDER_ERROR", "NO_KEY"})
    void otherProviderErrorsAreNotRetried(ChatErrorKind kind) {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.failAlways(error(kind));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.FAILED);

        assertThat(embeddings.batches()).hasSize(1);
        assertThat((String) support.row(id).get("index_error")).contains(kind.name());
    }

    @Test
    void contentWithoutAnyChunkFailsWithoutCallingTheProvider() {
        long id = support.document("a", "## Chỉ có tiêu đề", "PUBLISHED", 1, 0, "PENDING");

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.FAILED);

        assertThat(embeddings.calls()).isZero();
        assertThat((String) support.row(id).get("index_error")).contains("không có đoạn");
        assertThat(support.chunkCount(id)).isZero();
    }

    // ---------------------------------------------------------------- phiên bản đổi giữa chừng

    @Test
    void aVersionBumpDuringEmbeddingDiscardsTheRunAndLeavesTheDocumentPending() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.beforeCall(() -> jdbc.update(
                "UPDATE ai_documents SET version = version + 1, index_status = 'PENDING' WHERE id = ?", id));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.STALE);

        assertThat(support.chunkCount(id)).isZero();
        Map<String, Object> row = support.row(id);
        assertThat(row.get("index_status")).isEqualTo("PENDING");
        assertThat(row.get("indexed_version")).isEqualTo(0);
    }

    @Test
    void archivingDuringEmbeddingDiscardsTheRun() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        embeddings.beforeCall(() -> jdbc.update("UPDATE ai_documents SET status = 'ARCHIVED' WHERE id = ?", id));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.STALE);

        assertThat(support.chunkCount(id)).isZero();
        assertThat(support.row(id).get("status")).isEqualTo("ARCHIVED");
    }

    @Test
    void draftArchivedAndMissingDocumentsAreSkippedWithoutCallingTheProvider() {
        long draft = support.document("draft", AiItSupport.content(2), "DRAFT", 1, 0, "PENDING");
        long archived = support.document("archived", AiItSupport.content(2), "ARCHIVED", 1, 0, "PENDING");

        assertThat(indexer.indexDocument(draft)).isEqualTo(Result.SKIPPED);
        assertThat(indexer.indexDocument(archived)).isEqualTo(Result.SKIPPED);
        assertThat(indexer.indexDocument(Long.MAX_VALUE)).isEqualTo(Result.SKIPPED);

        assertThat(embeddings.calls()).isZero();
        assertThat(support.chunkCount(draft) + support.chunkCount(archived)).isZero();
    }

    // ---------------------------------------------------------------- transaction và kết nối

    @Test
    void noDatabaseConnectionIsHeldWhileTheProviderIsCalled() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        AtomicInteger activeDuringEmbedding = new AtomicInteger(-1);
        embeddings.beforeCall(() -> activeDuringEmbedding.set(hikari.getHikariPoolMXBean().getActiveConnections()));

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.INDEXED);

        assertThat(activeDuringEmbedding.get()).isZero();
    }

    @Test
    void aRejectedFinalUpdateRollsTheWholeSwapBackAndTheOldChunksKeepServing() {
        long id = indexedAtVersion1ThenEditedToVersion2();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION ai_it_reject_indexed() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.index_status = 'INDEXED' THEN
                        RAISE EXCEPTION 'IT: từ chối ghi INDEXED';
                    END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER ai_it_reject_indexed BEFORE UPDATE ON ai_documents FOR EACH ROW "
                + "EXECUTE FUNCTION ai_it_reject_indexed()");

        assertThat(indexer.indexDocument(id)).isEqualTo(Result.FAILED);

        assertThat(support.chunks(id)).extracting(c -> c.get("doc_version")).containsExactly(1, 1);
        assertThat(support.chunks(id)).extracting(c -> c.get("text")).containsExactly("cũ 1", "cũ 2");
        Map<String, Object> row = support.row(id);
        assertThat(row.get("index_status")).isEqualTo("FAILED");
        assertThat(row.get("indexed_version")).isEqualTo(1);
        assertThat(support.retrieve(FakeEmbeddingClient.vectorOf("cũ 2"))).hasSize(2);
    }

    // ---------------------------------------------------------------- quét tài liệu cần nạp

    @Test
    void scanSelectsOnlyPublishedDocumentsWithoutAnUpToDateIndex() {
        long pending = support.document("pending", AiItSupport.content(1), "PUBLISHED", 1, 0, "PENDING");
        long failed = support.document("failed", AiItSupport.content(1), "PUBLISHED", 1, 1, "FAILED");
        long outdated = support.document("outdated", AiItSupport.content(1), "PUBLISHED", 2, 1, "INDEXED");
        long current = support.document("current", AiItSupport.content(1), "PUBLISHED", 1, 1, "INDEXED");
        long draft = support.document("draft", AiItSupport.content(1), "DRAFT", 1, 0, "PENDING");
        long archived = support.document("archived", AiItSupport.content(1), "ARCHIVED", 1, 0, "PENDING");

        List<Long> ids = docs.findIdsNeedingIndex();

        assertThat(ids).contains(pending, failed, outdated).doesNotContain(current, draft, archived);
    }

    @Test
    void requestsAreIgnoredWhileIndexingIsDisabled() {
        long id = support.document("a", AiItSupport.content(2), "PUBLISHED", 1, 0, "PENDING");

        indexer.requestIndex(id);
        indexer.requestPending();

        assertThat(embeddings.calls()).isZero();
        assertThat(support.row(id).get("index_status")).isEqualTo("PENDING");
    }
}
