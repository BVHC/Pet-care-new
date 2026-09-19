package com.petcare.module.organization.entity;

import com.petcare.platform.enums.SurchargeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Child Entity Module 03 (Organization & Store Management) — docs/06-erd.md bảng store_policies
 * (RULE-03-10). Không extend BaseEntity — bảng không có deleted_at (không có khái niệm soft-delete
 * cho 1 dòng settings 1:1/Store), nhưng vẫn dùng AuditingEntityListener cho
 * created_at/updated_at/created_by/updated_by (tái dùng AuditorAware có sẵn) và có riêng
 * @Version — PATCH policy có khả năng đụng độ đồng thời thực sự, cùng lý do OrganizationPolicy.
 */
@Entity
@Table(name = "store_policies")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class StorePolicy {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "surcharge_enabled", nullable = false)
    private boolean surchargeEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "surcharge_type", length = 20)
    private SurchargeType surchargeType;

    @Column(name = "surcharge_value", precision = 12, scale = 2)
    private BigDecimal surchargeValue;

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

    public StorePolicy(UUID storeId) {
        this.storeId = storeId;
    }
}
