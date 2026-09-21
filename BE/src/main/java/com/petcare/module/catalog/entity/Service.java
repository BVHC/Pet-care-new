package com.petcare.module.catalog.entity;

import com.petcare.platform.enums.ServiceCategory;
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
 * services. RULE-05-03 (Organization Admin quản lý tập trung ManageService).
 * Tên class "Service" theo docs/04-glossary.md trùng
 * {@code org.springframework.stereotype.Service} — nơi cần cả hai (impl class) phải
 * fully-qualify annotation Spring, không đổi tên entity để né.
 */
@Entity
@Table(name = "services")
@Getter
@Setter
@NoArgsConstructor
public class Service extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private ServiceCategory category;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    public Service(UUID organizationId, String code, String name, ServiceCategory category, BigDecimal basePrice,
                    Integer durationMinutes, Boolean isActive) {
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.category = category;
        this.basePrice = basePrice;
        this.durationMinutes = durationMinutes == null ? 30 : durationMinutes;
        this.isActive = isActive == null || isActive;
    }
}
