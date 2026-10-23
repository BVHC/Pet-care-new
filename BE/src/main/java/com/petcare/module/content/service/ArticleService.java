package com.petcare.module.content.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.dto.ArticleResponse;
import com.petcare.module.content.dto.ArticleSummaryResponse;
import com.petcare.module.content.dto.CreateArticleRequest;
import com.petcare.module.content.dto.UpdateArticleRequest;
import com.petcare.module.content.entity.Article;
import com.petcare.module.content.entity.ArticleStatus;
import com.petcare.module.content.repository.ArticleCategoryRepository;
import com.petcare.module.content.repository.ArticleRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import jakarta.persistence.criteria.Predicate;

/**
 * Bài viết (content-v1 #7–13; UC81; BR-BV-01, 02). Vòng đời chỉ có rule ({@code DRAFT → PUBLISHED ⇄ HIDDEN}, 03 không
 * có bảng): guard trong service, sai trạng thái là 409. Thứ tự khóa {@code article_categories (FOR SHARE) → articles
 * (FOR NO KEY UPDATE)}: tạo / chuyển bài khóa chuyên mục đích trước, nên xóa chuyên mục (khóa ghi rồi đếm) không bao
 * giờ để lại bài mồ côi. Nội dung lưu bản đã lọc ({@link ArticleHtml}). Không audit.
 */
@Service
public class ArticleService {

    static final int MAX_PAGE_SIZE = 100;

    private final ArticleRepository articles;
    private final ArticleCategoryRepository categories;
    private final BranchScope scope;
    private final Clock clock;

    public ArticleService(ArticleRepository articles, ArticleCategoryRepository categories, BranchScope scope,
            Clock clock) {
        this.articles = articles;
        this.categories = categories;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ArticleSummaryResponse> list(ArticleStatus status, Long categoryId, String q, int page,
            int size) {
        Specification<Article> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (q != null && !q.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")),
                        "%" + ContentText.escapeLike(ContentText.normalize(q).toLowerCase()) + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var result = articles.findAll(spec, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "updatedAt", "id")));
        return PageResponse.of(result.map(ArticleService::toSummary));
    }

    @Transactional(readOnly = true)
    public ArticleResponse get(Long articleId) {
        return toResponse(articles.findById(articleId).orElseThrow(() -> notFound(articleId)));
    }

    @Transactional
    public ArticleResponse create(CreateArticleRequest request) {
        Long actorId = scope.current().accountId();
        String content = cleanContent(request.content());
        categories.lockForShare(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("chuyên mục", request.categoryId()));
        if (articles.existsBySlug(request.slug())) {
            throw new BusinessRuleViolationException("BR-BV-01", "Đường dẫn bài viết đã tồn tại");
        }
        Article article = new Article(request.categoryId(), ContentText.normalize(request.title()), request.slug(),
                blankToNull(request.coverImageUrl()), blankToNull(ContentText.normalize(request.summary())), content,
                ContentText.normalize(request.authorDisplayName()), actorId);
        return toResponse(articles.saveAndFlush(article));
    }

    @Transactional
    public ArticleResponse update(Long articleId, UpdateArticleRequest request) {
        if (request.categoryId() != null) {
            categories.lockForShare(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("chuyên mục", request.categoryId()));
        }
        Article article = articles.findByIdForUpdate(articleId).orElseThrow(() -> notFound(articleId));
        if (request.slug() != null && !request.slug().equals(article.getSlug())) {
            if (article.getFirstPublishedAt() != null) {
                throw new BusinessRuleViolationException("BR-BV-01",
                        "Không đổi được đường dẫn sau lần xuất bản đầu tiên");
            }
            if (articles.existsBySlugAndIdNot(request.slug(), articleId)) {
                throw new BusinessRuleViolationException("BR-BV-01", "Đường dẫn bài viết đã tồn tại");
            }
            article.setSlug(request.slug());
        }
        if (request.categoryId() != null) {
            article.setCategoryId(request.categoryId());
        }
        if (request.title() != null) {
            article.setTitle(ContentText.normalize(request.title()));
        }
        if (request.coverImageUrl() != null) {
            article.setCoverImageUrl(blankToNull(request.coverImageUrl()));
        }
        if (request.summary() != null) {
            article.setSummary(blankToNull(ContentText.normalize(request.summary())));
        }
        if (request.content() != null) {
            article.setContent(cleanContent(request.content()));
        }
        if (request.authorDisplayName() != null) {
            article.setAuthorDisplayName(ContentText.normalize(request.authorDisplayName()));
        }
        return toResponse(articles.saveAndFlush(article));
    }

    /** Bài đang PUBLISHED thì 409; lần đầu ghi {@code first_published_at} và khóa slug (BR-BV-01, 02). */
    @Transactional
    public ArticleResponse publish(Long articleId) {
        Article article = articles.findByIdForUpdate(articleId).orElseThrow(() -> notFound(articleId));
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            throw new InvalidStateTransitionException("Article", article.getStatus().name(),
                    ArticleStatus.PUBLISHED.name());
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        article.setStatus(ArticleStatus.PUBLISHED);
        if (article.getFirstPublishedAt() == null) {
            article.setFirstPublishedAt(now);
        }
        return toResponse(articles.saveAndFlush(article));
    }

    @Transactional
    public ArticleResponse hide(Long articleId) {
        Article article = articles.findByIdForUpdate(articleId).orElseThrow(() -> notFound(articleId));
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new InvalidStateTransitionException("Article", article.getStatus().name(),
                    ArticleStatus.HIDDEN.name());
        }
        article.setStatus(ArticleStatus.HIDDEN);
        return toResponse(articles.saveAndFlush(article));
    }

    /** Bài đã từng xuất bản (kể cả đang HIDDEN) không xóa được, chỉ ẩn (BR-BV-02). */
    @Transactional
    public void delete(Long articleId) {
        Article article = articles.findByIdForUpdate(articleId).orElseThrow(() -> notFound(articleId));
        if (article.getFirstPublishedAt() != null) {
            throw new BusinessRuleViolationException("BR-BV-02", "Bài viết đã từng xuất bản, chỉ ẩn được");
        }
        articles.delete(article);
        articles.flush();
    }

    private static String cleanContent(String html) {
        String cleaned = ArticleHtml.clean(ContentText.normalize(html));
        if (!ArticleHtml.hasText(cleaned)) {
            throw new BusinessRuleViolationException("BR-BV-01", "Nội dung bài viết trống sau khi lọc");
        }
        return cleaned;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static ResourceNotFoundException notFound(Long articleId) {
        return new ResourceNotFoundException("bài viết", articleId);
    }

    static ArticleResponse toResponse(Article a) {
        return new ArticleResponse(a.getId(), a.getCategoryId(), a.getTitle(), a.getSlug(), a.getCoverImageUrl(),
                a.getSummary(), a.getContent(), a.getAuthorDisplayName(), a.getStatus(),
                micros(a.getFirstPublishedAt()), micros(a.getCreatedAt()), micros(a.getUpdatedAt()));
    }

    private static ArticleSummaryResponse toSummary(Article a) {
        return new ArticleSummaryResponse(a.getId(), a.getCategoryId(), a.getTitle(), a.getSlug(), a.getStatus(),
                micros(a.getFirstPublishedAt()), micros(a.getUpdatedAt()));
    }

    static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
