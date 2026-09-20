package com.petcare.module.organization.entity;

import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Aggregate Root Module 03 (Organization & Store Management) — docs/06-erd.md
 * bảng stores, cùng Bounded Context với Organization (docs/05-domain-model.md
 * §4.3: 2 Aggregate Root trong 1 module) nên ở chung package. FSM-2
 * (docs/03-state-machines.md §2) — khởi tạo luôn ở DRAFT qua CreateStore;
 * Activate/Suspend/Deactivate/Archive để triển khai sau.
 */
@Entity
@Table(name = "stores")
@Getter
@Setter
@NoArgsConstructor
public class Store extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "facility_type", nullable = false)
    private FacilityType facilityType = FacilityType.RETAIL_STORE;

    @Column(name = "address", columnDefinition = "TEXT", nullable = false)
    private String address;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private StoreStatus status = StoreStatus.DRAFT;

    public Store(UUID organizationId, String code, String name, FacilityType facilityType, String address, String phone) {
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.facilityType = facilityType;
        this.address = address;
        this.phone = phone;
        this.status = StoreStatus.DRAFT;
    }
}
