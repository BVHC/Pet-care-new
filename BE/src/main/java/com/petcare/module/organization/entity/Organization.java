package com.petcare.module.organization.entity;

import com.petcare.platform.enums.OrganizationStatus;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Aggregate Root Module 03 (Organization & Store Management) — docs/06-erd.md
 * bảng organizations. Tenant cha sở hữu Store; không có FSM trong v1
 * (docs/03-state-machines.md chỉ đặc tả FSM-2 cho Store, không cho Organization) —
 * status chỉ được set mặc định ACTIVE khi tạo, không có command đổi trạng thái.
 */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class Organization extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "tax_code", length = 50)
    private String taxCode;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    public Organization(String code, String name, String taxCode, String address) {
        this.code = code;
        this.name = name;
        this.taxCode = taxCode;
        this.address = address;
        this.status = OrganizationStatus.ACTIVE;
    }
}
