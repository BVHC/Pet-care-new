package com.petcare.module.organization.entity;

import com.petcare.platform.enums.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child Entity Module 03 (Organization & Store Management) — docs/06-erd.md bảng
 * store_resources. Không extend BaseEntity — bảng không có created_by/updated_by/deleted_at/
 * version (giống OperatingHour/Otp/RefreshTokenEntity). RULE-03-02/08 — ConfigureStoreResource
 * chỉ STORE_MANAGER đúng Store mình quản lý (quyết định 2026-09-17, bám literal
 * docs/01-business-operations.md 01#3, cùng logic đã chốt cho ConfigureOperatingHour). Không có
 * @Version — ERD không có cột version cho bảng này (khác Store), PATCH là update từng field đơn lẻ
 * (không phải replace-all như OperatingHour) nên dirty-checking JPA thông thường là đủ.
 */
@Entity
@Table(name = "store_resources")
@Getter
@Setter
@NoArgsConstructor
public class StoreResource {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "resource_code", nullable = false, length = 50)
    private String resourceCode;

    @Column(name = "resource_name", nullable = false, length = 100)
    private String resourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 50)
    private ResourceType resourceType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StoreResource(UUID storeId, String resourceCode, String resourceName, ResourceType resourceType,
                          boolean active) {
        this.storeId = storeId;
        this.resourceCode = resourceCode;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.active = active;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
