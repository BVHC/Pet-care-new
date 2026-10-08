package com.petcare.module.catalog.entity;

import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
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
 * Bảng {@code services} (erd §4, ROOT). Gồm cả loại chuồng (nhóm {@code BOARDING}, BR-SP-04). Nhóm dịch vụ không
 * đổi sau khi tạo (catalog-v1 A1).
 */
@Getter
@Entity
@Table(name = "services")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Service extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false)
    private ServiceGroup group;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "medical_type")
    private MedicalType medicalType;

    @Setter
    @Column(name = "price", nullable = false)
    private long price;

    @Setter
    @Column(name = "price_is_from", nullable = false)
    private boolean priceIsFrom = true;

    @Setter
    @Column(name = "description")
    private String description;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Service(String name, ServiceGroup group, MedicalType medicalType, long price, boolean priceIsFrom,
            String description) {
        this.name = name;
        this.group = group;
        this.medicalType = medicalType;
        this.price = price;
        this.priceIsFrom = priceIsFrom;
        this.description = description;
    }
}
