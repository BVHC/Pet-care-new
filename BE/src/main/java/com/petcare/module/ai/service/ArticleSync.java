package com.petcare.module.ai.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.repository.AiDocumentRepository.ArticleDocument;
import com.petcare.module.content.api.ContentQueryApi;
import com.petcare.module.content.api.ContentQueryApi.ArticleInfo;
import com.petcare.platform.config.TimeConfig;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * ST22 — đồng bộ bài viết đã xuất bản vào kho tri thức (07 §5.3, BR-AI-30, I-12, docs/adr/0134 "Đã cài ở T90"), cùng chu
 * kỳ với {@link CatalogProtocolSync}. Mỗi bài một tài liệu {@code MANUAL} có {@code external_id = ARTICLE-<id>}; luật theo
 * trạng thái nằm ở {@link ArticleWriter} (mỗi bài một transaction, nên một bài lỗi chỉ bị log
 * {@code ARTICLE_SYNC_FAILED} và lượt sau thử lại, không chặn bài khác). Văn bản là Markdown dựng từ HTML của bài
 * ({@link ArticleText}); bài dài quá giới hạn của tài liệu hoặc không có chữ thì bỏ qua, không cắt, và được đếm ở
 * {@code ai.article.sync}. Tài liệu {@code PUBLISHED} của bài không còn xuất bản bị lưu trữ ({@code
 * ARTICLE_ARCHIVE_FAILED} khi lỗi, cùng cách cô lập). Không {@code @Transactional}: không giữ kết nối trong lúc gọi
 * {@link ContentQueryApi}.
 */
@Slf4j
@Service
public class ArticleSync {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(TimeConfig.BUSINESS_ZONE);

    private final ContentQueryApi content;
    private final ArticleWriter writer;
    private final AiDocumentRepository docs;
    private final MeterRegistry meters;

    public ArticleSync(ContentQueryApi content, ArticleWriter writer, AiDocumentRepository docs, MeterRegistry meters) {
        this.content = content;
        this.writer = writer;
        this.docs = docs;
        this.meters = meters;
    }

    /** Trả số tài liệu đã tạo mới, đổi nội dung, hạ về nháp, xuất bản lại hoặc lưu trữ. */
    public int sync() {
        // Liệt kê TRƯỚC khi đọc bài: tài liệu do một lượt khác tạo trong lúc này không có trong danh sách, nên không bị coi
        // nhầm là của bài đã gỡ.
        List<ArticleDocument> published = docs.findPublishedArticleDocuments();
        List<ArticleInfo> articles = content.listPublishedArticles(Instant.EPOCH);
        Set<String> stillPublished = new HashSet<>();
        int changed = 0;
        for (ArticleInfo article : articles) {
            stillPublished.add(ArticleWriter.PREFIX + article.articleId());
            try {
                if (write(article)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                meters.counter("ai.article.sync", "outcome", "failed").increment();
                log.error("ARTICLE_SYNC_FAILED articleId={}", article.articleId(), e);
            }
        }
        return changed + archiveRemoved(published, stillPublished);
    }

    private boolean write(ArticleInfo article) {
        String text = ArticleText.plain(article.content());
        if (text.isEmpty()) {
            meters.counter("ai.article.sync", "outcome", "empty").increment();
            return false;
        }
        if (text.length() > AiDocument.CONTENT_MAX_LENGTH) {
            meters.counter("ai.article.sync", "outcome", "too_long").increment();
            log.warn("ARTICLE_SYNC_TOO_LONG articleId={} length={}", article.articleId(), text.length());
            return false;
        }
        String title = article.title().length() <= AiDocument.TITLE_MAX_LENGTH ? article.title()
                : article.title().substring(0, AiDocument.TITLE_MAX_LENGTH);
        String note = "Bài viết #" + article.articleId() + " của " + article.authorDisplayName() + ", xuất bản "
                + DAY.format(article.firstPublishedAt());
        return writer.upsert(article.articleId(), title, text, note, Sha256.hex(article.title() + "\n\n" + text));
    }

    /** Chạy sau khi đã đọc đủ bài: một lần đọc {@code content} lỗi làm {@link #sync()} ném trước bước này. */
    private int archiveRemoved(List<ArticleDocument> published, Set<String> stillPublished) {
        int archived = 0;
        for (ArticleDocument document : published) {
            if (stillPublished.contains(document.getExternalId())) {
                continue;
            }
            try {
                if (writer.archiveRemoved(document.getId())) {
                    archived++;
                }
            } catch (RuntimeException e) {
                log.error("ARTICLE_ARCHIVE_FAILED documentId={}", document.getId(), e);
            }
        }
        return archived;
    }
}
