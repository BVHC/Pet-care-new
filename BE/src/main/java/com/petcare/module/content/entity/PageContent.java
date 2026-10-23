package com.petcare.module.content.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bảng {@code page_contents} (erd §10, REF): nội dung trang tĩnh; khóa là {@link PageKey#name()}. */
@Getter
@Entity
@Table(name = "page_contents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageContent extends TimestampedEntity {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Setter
    @Column(name = "title")
    private String title;

    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private JsonNode content;

    @Setter
    @Column(name = "updated_by")
    private Long updatedBy;
}
