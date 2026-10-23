package com.petcare.module.content.entity;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bảng {@code article_categories} (erd §10, REF): còn bài thì chỉ ẩn, không xóa (BR-BV-04). Slug không đổi (A1). */
@Getter
@Entity
@Table(name = "article_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleCategory extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Setter
    @Column(name = "is_hidden", nullable = false)
    private boolean hidden;

    public ArticleCategory(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }
}
