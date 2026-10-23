package com.petcare.module.content.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.dto.PublicArticleCardResponse;
import com.petcare.module.content.dto.PublicArticleResponse;
import com.petcare.module.content.entity.Article;
import com.petcare.module.content.entity.ArticleCategory;
import com.petcare.module.content.entity.ArticleStatus;
import com.petcare.module.content.repository.ArticleCategoryRepository;
import com.petcare.module.content.repository.ArticleRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;

/**
 * Bài viết công khai (content-v1 #16, #17; UC17; BR-BV-03, BR-CK-01). Chỉ {@code PUBLISHED}, mới nhất trước. Bài DRAFT,
 * HIDDEN và slug không có cùng một 404 (không lộ bài tồn tại). Chuyên mục ẩn chỉ biến khỏi bộ lọc công khai: bài trong
 * đó vẫn đọc được theo đường dẫn và vẫn lọc được bằng {@code categorySlug} (BR-BV-04, D6 của T32).
 */
@Service
@Transactional(readOnly = true)
public class PublicArticleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ArticleRepository articles;
    private final ArticleCategoryRepository categories;

    public PublicArticleService(ArticleRepository articles, ArticleCategoryRepository categories) {
        this.articles = articles;
        this.categories = categories;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PublicArticleCardResponse> list(String categorySlug, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "firstPublishedAt", "id"));
        Long categoryId = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categoryId = categories.findBySlug(categorySlug).map(ArticleCategory::getId).orElse(null);
            if (categoryId == null) {
                return PageResponse.of(new PageImpl<>(List.of(), pageable, 0));
            }
        }
        Long filterCategory = categoryId;
        Specification<Article> spec = (root, query, cb) -> filterCategory == null
                ? cb.equal(root.get("status"), ArticleStatus.PUBLISHED)
                : cb.and(cb.equal(root.get("status"), ArticleStatus.PUBLISHED),
                        cb.equal(root.get("categoryId"), filterCategory));
        Page<Article> result = articles.findAll(spec, pageable);
        Map<Long, ArticleCategory> byId = categories.findAllById(
                result.getContent().stream().map(Article::getCategoryId).distinct().toList()).stream()
                .collect(Collectors.toMap(ArticleCategory::getId, Function.identity()));
        return PageResponse.of(result.map(a -> new PublicArticleCardResponse(a.getId(), a.getTitle(), a.getSlug(),
                byId.get(a.getCategoryId()).getName(), byId.get(a.getCategoryId()).getSlug(), a.getCoverImageUrl(),
                a.getSummary(), a.getAuthorDisplayName(), ArticleService.micros(a.getFirstPublishedAt()))));
    }

    public PublicArticleResponse get(String slug) {
        Article article = articles.findBySlug(slug).filter(a -> a.getStatus() == ArticleStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("bài viết", slug));
        ArticleCategory category = categories.findById(article.getCategoryId()).orElseThrow();
        return new PublicArticleResponse(article.getId(), article.getTitle(), article.getSlug(), category.getName(),
                category.getSlug(), article.getCoverImageUrl(), article.getSummary(), article.getContent(),
                article.getAuthorDisplayName(), ArticleService.micros(article.getFirstPublishedAt()));
    }
}
