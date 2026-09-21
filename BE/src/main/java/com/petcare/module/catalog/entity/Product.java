package com.petcare.module.catalog.entity;

import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregate Root Module 05 (Service &amp; Product Catalog) — docs/06-erd.md bảng
 * products, docs/05-domain-model.md §4.5 (glossary gọi "Product", domain model gọi
 * "ProductMaster" — dùng "Product" theo docs/04-glossary.md, nguồn đặt tên class chính
 * thức). RULE-05-01 (Organization Admin sở hữu toàn quyền), RULE-05-02 (UNIQUE
 * (organization_id, sku); không hard-delete khi đã phát sinh giao dịch, chỉ
 * isActive=false). category/unit là VARCHAR thường trong DB (không có Postgres native
 * enum type như facility_type_enum) nên không dùng @JdbcTypeCode(NAMED_ENUM).
 * isFractional/baseUnit/purchaseUnitConversionFactor thuộc ERD nhưng phục vụ Module 12
 * (Inventory) chưa triển khai — Catalog API không expose, luôn giữ giá trị mặc định.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Column(name = "barcode", length = 50)
    private String barcode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private ProductCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 20)
    private ProductUnit unit = ProductUnit.ITEM;

    @Column(name = "is_fractional", nullable = false)
    private boolean isFractional = false;

    @Column(name = "base_unit", nullable = false, length = 10)
    private String baseUnit = "UNIT";

    @Column(name = "purchase_unit_conversion_factor", nullable = false)
    private int purchaseUnitConversionFactor = 1;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    public Product(UUID organizationId, String sku, String barcode, String name, ProductCategory category,
                    ProductUnit unit, BigDecimal basePrice, BigDecimal costPrice, Boolean isActive) {
        this.organizationId = organizationId;
        this.sku = sku;
        this.barcode = barcode;
        this.name = name;
        this.category = category;
        this.unit = unit;
        this.basePrice = basePrice;
        this.costPrice = costPrice;
        this.isActive = isActive == null || isActive;
    }
}
