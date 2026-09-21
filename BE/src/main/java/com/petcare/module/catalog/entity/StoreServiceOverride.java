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
 * Child Entity Module 05 — docs/06-erd.md bảng store_services (RULE-05-04, RULE-05-05,
 * RULE-05-07). Override giá + khả dụng riêng theo từng Store; {@code isActive} ở đây KHÔNG
 * đụng {@link Service#isActive()} gốc của Organization (RULE-05-04). Cùng pattern
 * {@link StoreProductOverride} — không BaseEntity, @Version riêng cho PUT đồng thời. Tên
 * "StoreServiceOverride" (không phải "StoreService") theo docs/04-glossary.md §05 và để tránh
 * trùng {@code com.petcare.module.organization.service.StoreService}.
 */
@Entity
@Table(name = "store_services")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StoreServiceOverride {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

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

    public StoreServiceOverride(UUID storeId, UUID serviceId, BigDecimal price, boolean isActive) {
        this.storeId = storeId;
        this.serviceId = serviceId;
        this.price = price;
        this.isActive = isActive;
    }
}
