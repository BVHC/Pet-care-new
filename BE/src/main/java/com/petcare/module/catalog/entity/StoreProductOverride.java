package com.petcare.module.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child Entity Module 05 — docs/06-erd.md bảng store_products (RULE-05-05, RULE-05-07).
 * Override giá riêng theo từng Store, kế thừa mặc định từ {@link Product#getBasePrice()} khi
 * Store chuyển ACTIVE. Không extend BaseEntity — không có deleted_at (không có khái niệm
 * soft-delete cho 1 dòng override, chỉ bị ghi đè qua PUT), nhưng có @Version riêng — PUT giá
 * đồng thời từ Store Manager là race thật, cùng lý do StorePolicy. Tên "StoreProductOverride"
 * (không phải "StoreProduct") theo đúng docs/04-glossary.md §05.
 */
@Entity
@Table(name = "store_products")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StoreProductOverride {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public StoreProductOverride(UUID storeId, UUID productId, BigDecimal price) {
        this.storeId = storeId;
        this.productId = productId;
        this.price = price;
    }
}
