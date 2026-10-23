package com.petcare.module.content.entity;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bảng {@code articles} (erd §10, ROOT). {@code first_published_at} khác NULL thì không xóa, không đổi slug
 * (BR-BV-01, 02). {@code content} là HTML đã lọc ({@code ArticleHtml}).
 */
@Getter
@Entity
@Table(name = "articles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Article extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Setter
    @Column(name = "title", nullable = false)
    private String title;

    @Setter
    @Column(name = "slug", nullable = false)
    private String slug;

    @Setter
    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Setter
    @Column(name = "summary")
    private String summary;

    @Setter
    @Column(name = "content", nullable = false)
    private String content;

    @Setter
    @Column(name = "author_display_name", nullable = false)
    private String authorDisplayName;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ArticleStatus status = ArticleStatus.DRAFT;

    @Setter
    @Column(name = "first_published_at")
    private Instant firstPublishedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    public Article(Long categoryId, String title, String slug, String coverImageUrl, String summary, String content,
            String authorDisplayName, Long createdBy) {
        this.categoryId = categoryId;
        this.title = title;
        this.slug = slug;
        this.coverImageUrl = coverImageUrl;
        this.summary = summary;
        this.content = content;
        this.authorDisplayName = authorDisplayName;
        this.createdBy = createdBy;
    }
}
