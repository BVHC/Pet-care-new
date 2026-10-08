package com.petcare.module.catalog.entity;

import com.petcare.module.catalog.api.ProductType;
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
 * Bảng {@code products} (erd §4, ROOT). Loại sản phẩm và SKU không đổi sau khi tạo (catalog-v1 A1). BR-SP-01, 05, 07
 * kiểm ở {@code ProductService}; DB có CHECK trùng để chặn lỗi lập trình.
 */
@Getter
@Entity
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "sku", nullable = false)
    private String sku;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false)
    private ProductType productType;

    @Setter
    @Column(name = "is_prescription", nullable = false)
    private boolean prescription;

    @Setter
    @Column(name = "tracks_expiry", nullable = false)
    private boolean tracksExpiry;

    @Setter
    @Column(name = "vaccine_type_id")
    private Long vaccineTypeId;

    @Setter
    @Column(name = "unit", nullable = false)
    private String unit;

    @Setter
    @Column(name = "price", nullable = false)
    private long price;

    @Setter
    @Column(name = "description")
    private String description;

    @Setter
    @Column(name = "image_url")
    private String imageUrl;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Product(Long categoryId, String sku, String name, ProductType productType, boolean prescription,
            boolean tracksExpiry, Long vaccineTypeId, String unit, long price, String description, String imageUrl) {
        this.categoryId = categoryId;
        this.sku = sku;
        this.name = name;
        this.productType = productType;
        this.prescription = prescription;
        this.tracksExpiry = tracksExpiry;
        this.vaccineTypeId = vaccineTypeId;
        this.unit = unit;
        this.price = price;
        this.description = description;
        this.imageUrl = imageUrl;
    }
}
