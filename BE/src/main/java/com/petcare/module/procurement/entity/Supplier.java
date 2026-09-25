package com.petcare.module.procurement.entity;

import com.petcare.platform.enums.SupplierStatus;
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
 * Aggregate Root Module 13 (Procurement) — docs/06-erd.md §3.5 bảng suppliers (RULE-13-04:
 * "Nhà cung cấp hợp lệ đang ở trạng thái ACTIVE do Organization Admin quản lý"). CRUD thuần,
 * không có cặp Maker-Checker nào — extends BaseEntity đầy đủ, cùng loài Product/Store (khác
 * PurchaseRequest/PurchaseOrder có created_by nghiệp vụ trỏ users(id)).
 */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
public class Supplier extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    @Column(name = "address")
    private String address;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private SupplierStatus status = SupplierStatus.ACTIVE;

    public Supplier(UUID organizationId, String code, String name, String contactPhone,
                     String contactEmail, String address) {
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.contactPhone = contactPhone;
        this.contactEmail = contactEmail;
        this.address = address;
    }
}
