package com.petcare.module.organization.entity;

import com.petcare.platform.enums.SecurityFrameworkLevel;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child Entity Module 03 (Organization & Store Management) — docs/06-erd.md bảng
 * organization_policies (RULE-03-09). Không extend BaseEntity — bảng không có deleted_at (không
 * có khái niệm soft-delete cho 1 dòng settings 1:1/Organization), nhưng vẫn dùng
 * AuditingEntityListener cho created_at/updated_at/created_by/updated_by (tái dùng AuditorAware
 * có sẵn, không set thủ công) và có riêng @Version — PATCH policy có khả năng đụng độ đồng thời
 * thực sự (2 tab/2 actor cùng sửa 1 Organization), khác OperatingHour (replace-all) và
 * StoreResource (không có version trong ERD gốc).
 */
@Entity
@Table(name = "organization_policies")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class OrganizationPolicy {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "refund_window_days", nullable = false)
    private int refundWindowDays = 7;

    @Column(name = "refund_requires_approval", nullable = false)
    private boolean refundRequiresApproval = true;

    @Column(name = "data_retention_days", nullable = false)
    private int dataRetentionDays = 730;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_framework_level", nullable = false, length = 30)
    private SecurityFrameworkLevel securityFrameworkLevel = SecurityFrameworkLevel.STANDARD;

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

    public OrganizationPolicy(UUID organizationId) {
        this.organizationId = organizationId;
    }
}
