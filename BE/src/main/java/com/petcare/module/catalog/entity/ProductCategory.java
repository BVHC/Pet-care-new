package com.petcare.module.catalog.entity;

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

/** Bảng {@code product_categories} (erd §4, REF). Danh mục chỉ ẩn, không xóa (UC28). */
@Getter
@Entity
@Table(name = "product_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCategory extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public ProductCategory(String name) {
        this.name = name;
    }
}
