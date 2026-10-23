package com.petcare.module.content.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.entity.ArticleCategory;

public interface ArticleCategoryRepository extends JpaRepository<ArticleCategory, Long> {

    java.util.List<ArticleCategory> findByHiddenFalseOrderByNameAscIdAsc();

    Optional<ArticleCategory> findBySlug(String slug);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    boolean existsBySlug(String slug);

    /**
     * Khóa chia sẻ dòng chuyên mục (scalar, không nạp entity): tạo / chuyển bài vào chuyên mục xếp hàng với xóa
     * chuyên mục. Khóa FK {@code FOR KEY SHARE} của INSERT không xung đột với {@code FOR NO KEY UPDATE}, nên phải khóa
     * tường minh. Trả rỗng nếu chuyên mục không tồn tại.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM article_categories WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<Long> lockForShare(@Param("id") Long id);

    /** Khóa ghi (scalar) trước khi đếm bài và xóa; xung đột với {@link #lockForShare}. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM article_categories WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Long> lockForUpdate(@Param("id") Long id);
}
