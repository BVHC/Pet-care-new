package com.petcare.module.content.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owner: content (BV) · BE-2. Chỉ đọc bài viết cho module {@code ai}: ST22 đồng bộ bài đã xuất bản vào kho tri thức
 * (07 I-12, BR-AI-30, docs/adr/0134 D3). Thêm 2026-11-13 (T90) thay BE-2, chỉ thêm, không đổi gì có sẵn (tiền lệ AI-0);
 * chữ ký ở {@code docs/06} §6b hàng 20 là đề xuất. {@code content} trả HTML đã lọc ({@code ArticleHtml}) nguyên văn: bên đọc
 * tự lược thẻ.
 */
public interface ContentQueryApi {

    enum ArticleState { DRAFT, PUBLISHED, HIDDEN }

    record ArticleInfo(Long articleId, String title, String content, String authorDisplayName,
                       Instant firstPublishedAt, Instant updatedAt, ArticleState status) {}

    /** Bài {@code PUBLISHED} có {@code updatedAt >= since}, theo id tăng (ST22 truyền {@link Instant#EPOCH} để lấy đủ). */
    List<ArticleInfo> listPublishedArticles(Instant since);

    /** Bài ở mọi trạng thái; rỗng nếu không có id đó. */
    Optional<ArticleInfo> findArticle(Long articleId);
}
