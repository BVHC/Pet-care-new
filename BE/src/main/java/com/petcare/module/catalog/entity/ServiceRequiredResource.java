package com.petcare.module.catalog.entity;

import com.petcare.platform.enums.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Child Entity Module 05 — docs/06-erd.md bảng service_required_resources (RULE-05-03).
 * Không có audit columns (ERD chỉ có id/service_id/resource_type/quantity_required) — thuần
 * bản ghi con, replace-as-whole cùng Service.update (contract ASSUMPTION A1,
 * docs/api/catalog-v1.md §B). Dùng chung {@code platform.enums.ResourceType} với module
 * Organization/StoreResource — cùng 4 giá trị, tránh trùng enum.
 */
@Entity
@Table(name = "service_required_resources")
@Getter
@Setter
@NoArgsConstructor
public class ServiceRequiredResource {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 50)
    private ResourceType resourceType;

    @Column(name = "quantity_required", nullable = false)
    private int quantityRequired = 1;

    public ServiceRequiredResource(UUID serviceId, ResourceType resourceType, int quantityRequired) {
        this.serviceId = serviceId;
        this.resourceType = resourceType;
        this.quantityRequired = quantityRequired;
    }
}
