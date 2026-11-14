package com.petcare.module.content.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.api.ContentQueryApi;
import com.petcare.module.content.entity.Article;
import com.petcare.module.content.entity.ArticleStatus;
import com.petcare.module.content.repository.ArticleRepository;

/** {@link ContentQueryApi} cho ST22 của module ai (07 I-12, T90): đọc thuần, trong transaction của bên gọi hoặc một transaction chỉ đọc. */
@Service
@Transactional(readOnly = true)
public class ArticleQueryService implements ContentQueryApi {

    private final ArticleRepository articles;

    public ArticleQueryService(ArticleRepository articles) {
        this.articles = articles;
    }

    @Override
    public List<ArticleInfo> listPublishedArticles(Instant since) {
        return articles.findAll((root, query, cb) -> cb.and(cb.equal(root.get("status"), ArticleStatus.PUBLISHED),
                cb.greaterThanOrEqualTo(root.get("updatedAt"), since)), Sort.by("id")).stream()
                .map(ArticleQueryService::toInfo).toList();
    }

    @Override
    public Optional<ArticleInfo> findArticle(Long articleId) {
        return articles.findById(articleId).map(ArticleQueryService::toInfo);
    }

    private static ArticleInfo toInfo(Article article) {
        return new ArticleInfo(article.getId(), article.getTitle(), article.getContent(), article.getAuthorDisplayName(),
                article.getFirstPublishedAt(), article.getUpdatedAt(), ArticleState.valueOf(article.getStatus().name()));
    }
}
