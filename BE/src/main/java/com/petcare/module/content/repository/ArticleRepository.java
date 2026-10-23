package com.petcare.module.content.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.entity.Article;

import jakarta.persistence.LockModeType;

public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    Optional<Article> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    long countByCategoryId(Long categoryId);

    /** Số bài (mọi trạng thái) theo chuyên mục: cặp {@code [categoryId, count]}. */
    @Query("SELECT a.categoryId, COUNT(a) FROM Article a GROUP BY a.categoryId")
    java.util.List<Object[]> countByCategory();

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Article a WHERE a.id = :id")
    Optional<Article> findByIdForUpdate(@Param("id") Long id);
}
