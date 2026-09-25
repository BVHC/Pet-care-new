package com.petcare.module.procurement.entity;

import com.petcare.platform.enums.PurchaseOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Đơn đặt hàng nhà cung cấp Module 13 — docs/06-erd.md §3.5 bảng purchase_orders (RULE-13-04:
 * khởi tạo từ PurchaseRequest đã APPROVED + Supplier ACTIVE). Không extend BaseEntity —
 * {@code createdBy} là định danh nghiệp vụ trỏ {@code users(id)}, cùng lý do PurchaseRequest.
 * Phạm vi task hiện tại chỉ dừng ở {@code ISSUED} (không FSM transition nào khác — RULE-13-05→08
 * ngoài phạm vi), nên không có {@code PurchaseOrderTransitionHandler}.
 */
@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "po_number", nullable = false, length = 50)
    private String poNumber;

    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private PurchaseOrderStatus status = PurchaseOrderStatus.ISSUED;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public PurchaseOrder(String poNumber, UUID purchaseRequestId, UUID storeId, UUID supplierId, UUID createdBy) {
        this.poNumber = poNumber;
        this.purchaseRequestId = purchaseRequestId;
        this.storeId = storeId;
        this.supplierId = supplierId;
        this.createdBy = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
