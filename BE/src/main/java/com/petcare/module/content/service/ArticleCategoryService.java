package com.petcare.module.content.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.dto.ArticleCategoryRequest;
import com.petcare.module.content.dto.ArticleCategoryResponse;
import com.petcare.module.content.dto.PublicArticleCategoryResponse;
import com.petcare.module.content.dto.UpdateArticleCategoryRequest;
import com.petcare.module.content.entity.ArticleCategory;
import com.petcare.module.content.exception.ContentValidationException;
import com.petcare.module.content.repository.ArticleCategoryRepository;
import com.petcare.module.content.repository.ArticleRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Chuyên mục bài viết (content-v1 #3–6, #15; UC81, UC17; BR-BV-04). Còn bài thì chỉ ẩn, không xóa; chuyên mục ẩn không
 * hiện ở bộ lọc công khai nhưng bài trong đó vẫn đọc được theo đường dẫn. Tên / slug trùng là 400 {@code VALIDATION_FAILED}
 * (hợp đồng không gắn mã rule); hai request đồng thời cùng tên tới UNIQUE → 409 do handler chung. Thứ tự khóa
 * {@code article_categories → articles}: xóa khóa chuyên mục {@code FOR NO KEY UPDATE} rồi mới đếm bài; tạo / chuyển bài
 * khóa {@code FOR SHARE} (xem {@code ArticleService}). Không audit.
 */
@Service
public class ArticleCategoryService {

    private final ArticleCategoryRepository categories;
    private final ArticleRepository articles;

    public ArticleCategoryService(ArticleCategoryRepository categories, ArticleRepository articles) {
        this.categories = categories;
        this.articles = articles;
    }

    @Transactional(readOnly = true)
    public List<ArticleCategoryResponse> list() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : articles.countByCategory()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return categories.findAll(Sort.by("name", "id")).stream()
                .map(c -> toResponse(c, Math.toIntExact(counts.getOrDefault(c.getId(), 0L)))).toList();
    }

    @Transactional
    public ArticleCategoryResponse create(ArticleCategoryRequest request) {
        String name = ContentText.normalize(request.name());
        if (categories.existsByName(name)) {
            throw new ContentValidationException("Tên chuyên mục đã tồn tại");
        }
        if (categories.existsBySlug(request.slug())) {
            throw new ContentValidationException("Đường dẫn chuyên mục đã tồn tại");
        }
        return toResponse(categories.saveAndFlush(new ArticleCategory(name, request.slug())), 0);
    }

    @Transactional
    public ArticleCategoryResponse update(Long categoryId, UpdateArticleCategoryRequest request) {
        categories.lockForUpdate(categoryId).orElseThrow(() -> notFound(categoryId));
        ArticleCategory category = categories.findById(categoryId).orElseThrow(() -> notFound(categoryId));
        if (request.name() != null) {
            String name = ContentText.normalize(request.name());
            if (categories.existsByNameAndIdNot(name, categoryId)) {
                throw new ContentValidationException("Tên chuyên mục đã tồn tại");
            }
            category.setName(name);
        }
        if (request.isHidden() != null) {
            category.setHidden(request.isHidden());
        }
        ArticleCategory saved = categories.saveAndFlush(category);
        return toResponse(saved, Math.toIntExact(articles.countByCategoryId(categoryId)));
    }

    @Transactional
    public void delete(Long categoryId) {
        categories.lockForUpdate(categoryId).orElseThrow(() -> notFound(categoryId));
        long count = articles.countByCategoryId(categoryId);
        if (count > 0) {
            throw new BusinessRuleViolationException("BR-BV-04",
                    "Chuyên mục còn " + count + " bài viết, chỉ ẩn được");
        }
        categories.deleteById(categoryId);
        categories.flush();
    }

    @Transactional(readOnly = true)
    public List<PublicArticleCategoryResponse> listPublic() {
        return categories.findByHiddenFalseOrderByNameAscIdAsc()
                .stream().map(c -> new PublicArticleCategoryResponse(c.getId(), c.getName(), c.getSlug())).toList();
    }

    private static ResourceNotFoundException notFound(Long categoryId) {
        return new ResourceNotFoundException("chuyên mục", categoryId);
    }

    private static ArticleCategoryResponse toResponse(ArticleCategory c, int articleCount) {
        return new ArticleCategoryResponse(c.getId(), c.getName(), c.getSlug(), c.isHidden(), articleCount);
    }
}
