package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.repository.AiDocumentRepository.ArticleDocument;
import com.petcare.module.content.api.ContentQueryApi;
import com.petcare.module.content.api.ContentQueryApi.ArticleInfo;
import com.petcare.module.content.api.ContentQueryApi.ArticleState;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Điều phối của {@link ArticleSync} (BR-AI-30) với {@code ContentQueryApi}, {@link ArticleWriter} và repository giả: dựng văn
 * bản và mã băm, bỏ bài quá dài hoặc không có chữ, cô lập lỗi từng bài, lưu trữ phần bài không còn xuất bản. Luật từng
 * trạng thái nằm ở {@link ArticleWriter} và được {@code ArticleSyncIT} kiểm trên Postgres thật.
 */
class ArticleSyncTest {

    private final ContentQueryApi content = mock(ContentQueryApi.class);
    private final ArticleWriter writer = mock(ArticleWriter.class);
    private final AiDocumentRepository docs = mock(AiDocumentRepository.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private ArticleSync sync;

    @BeforeEach
    void setUp() {
        sync = new ArticleSync(content, writer, docs, meters);
        when(docs.findPublishedArticleDocuments()).thenReturn(List.of());
        when(content.listPublishedArticles(any())).thenReturn(List.of());
        when(writer.upsert(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private static ArticleInfo article(long id, String title, String html) {
        return new ArticleInfo(id, title, html, "Bác sĩ An", Instant.parse("2026-11-13T03:00:00Z"),
                Instant.parse("2026-11-13T04:00:00Z"), ArticleState.PUBLISHED);
    }

    private static ArticleDocument document(long id, String externalId) {
        ArticleDocument doc = mock(ArticleDocument.class);
        when(doc.getId()).thenReturn(id);
        when(doc.getExternalId()).thenReturn(externalId);
        return doc;
    }

    private double count(String outcome) {
        var counter = meters.find("ai.article.sync").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theExistingDocumentsAreListedBeforeTheArticlesAreRead() {
        sync.sync();

        InOrder order = inOrder(docs, content);
        order.verify(docs).findPublishedArticleDocuments();
        order.verify(content).listPublishedArticles(Instant.EPOCH);
    }

    @Test
    void eachPublishedArticleIsWrittenWithItsRenderedTextHashAndSourceNote() {
        when(content.listPublishedArticles(any())).thenReturn(List.of(article(7, "Tắm cho mèo", "<h2>Tắm</h2><p>Hai tuần.</p>")));

        int changed = sync.sync();

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(writer).upsert(eq(7L), eq("Tắm cho mèo"), text.capture(), note.capture(), hash.capture());
        assertThat(text.getValue()).isEqualTo("## Tắm\n\nHai tuần.");
        assertThat(note.getValue()).isEqualTo("Bài viết #7 của Bác sĩ An, xuất bản 13/11/2026");
        assertThat(hash.getValue()).isEqualTo(Sha256.hex("Tắm cho mèo\n\n## Tắm\n\nHai tuần."));
    }

    @Test
    void aTitleLongerThanTheColumnIsCutToFit() {
        ArticleInfo longOne = new ArticleInfo(8L, "T".repeat(250), "<p>Chữ</p>", "Bác sĩ An",
                Instant.parse("2026-11-13T03:00:00Z"), Instant.parse("2026-11-13T04:00:00Z"), ArticleState.PUBLISHED);
        when(content.listPublishedArticles(any())).thenReturn(List.of(longOne));

        sync.sync();

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(writer).upsert(eq(8L), title.capture(), anyString(), anyString(), anyString());
        assertThat(title.getValue()).hasSize(200);
    }

    @Test
    void anArticleOverTheContentLimitIsSkippedAndCountedNotTruncated() {
        when(content.listPublishedArticles(any())).thenReturn(List.of(article(1, "Dài", "<p>" + "x".repeat(20_001) + "</p>"),
                article(2, "Vừa", "<p>" + "x".repeat(20_000) + "</p>")));

        sync.sync();

        verify(writer, never()).upsert(eq(1L), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(writer).upsert(eq(2L), anyString(), text.capture(), anyString(), anyString());
        assertThat(text.getValue()).hasSize(20_000);
        assertThat(count("too_long")).isEqualTo(1);
    }

    @Test
    void anArticleWithoutAnyTextIsSkipped() {
        when(content.listPublishedArticles(any())).thenReturn(List.of(article(3, "Chỉ ảnh", "<p> </p><img src=\"x.png\">")));

        sync.sync();

        verify(writer, never()).upsert(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertThat(count("empty")).isEqualTo(1);
    }

    @Test
    void aFailureOnOneArticleDoesNotStopTheOthersOrTheArchiving() {
        ArticleDocument gone = document(90, "ARTICLE-99");
        when(docs.findPublishedArticleDocuments()).thenReturn(List.of(gone));
        when(content.listPublishedArticles(any())).thenReturn(List.of(article(1, "Lỗi", "<p>Một</p>"), article(2, "Tốt", "<p>Hai</p>")));
        when(writer.upsert(eq(1L), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("IT"));
        when(writer.archiveRemoved(90L)).thenReturn(true);

        int changed = sync.sync();

        verify(writer).upsert(eq(2L), anyString(), anyString(), anyString(), anyString());
        verify(writer).archiveRemoved(90L);
        assertThat(changed).as("bài lỗi không tính").isEqualTo(2);
        assertThat(count("failed")).isEqualTo(1);
    }

    @Test
    void onlyDocumentsWhoseArticleIsNoLongerPublishedAreArchived() {
        ArticleDocument still = document(10, "ARTICLE-1");
        ArticleDocument gone = document(20, "ARTICLE-2");
        when(docs.findPublishedArticleDocuments()).thenReturn(List.of(still, gone));
        when(content.listPublishedArticles(any())).thenReturn(List.of(article(1, "Còn", "<p>Còn xuất bản</p>")));
        when(writer.archiveRemoved(anyLong())).thenReturn(true);

        sync.sync();

        verify(writer).archiveRemoved(20L);
        verify(writer, never()).archiveRemoved(10L);
    }

    @Test
    void ifReadingTheArticlesFailsNothingIsArchived() {
        ArticleDocument existing = document(10, "ARTICLE-1");
        when(docs.findPublishedArticleDocuments()).thenReturn(List.of(existing));
        when(content.listPublishedArticles(any())).thenThrow(new IllegalStateException("content tắt"));

        assertThatThrownBy(() -> sync.sync()).isInstanceOf(IllegalStateException.class);

        verify(writer, never()).archiveRemoved(anyLong());
    }
}
