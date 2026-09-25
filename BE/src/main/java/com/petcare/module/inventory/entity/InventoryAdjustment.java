package com.petcare.module.inventory.entity;

import com.petcare.platform.enums.AdjustmentReason;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phiếu điều chỉnh tồn kho Module 12 — docs/06-erd.md §3.5 bảng inventory_adjustments
 * (RULE-12-02 lý do hợp lệ, RULE-12-03 Maker-Checker). Không extend BaseEntity —
 * {@code createdBy}/{@code approvedBy} là cặp định danh maker-checker nghiệp vụ trỏ
 * {@code users(id)} (không phải {@code accounts(id)} của audit trail chung), và phiếu chỉ có
 * 2 hành động approve/reject, không có "sửa nội dung" hay soft-delete nào khác — cùng lý do
 * {@code StoreProductOverride} không extend BaseEntity. {@code reason}/{@code status} là
 * VARCHAR thường trong DB (không Postgres native enum), cùng tiền lệ
 * {@code Product.category}/{@code .unit} — {@code @Enumerated(STRING)} không cần
 * {@code @JdbcTypeCode(NAMED_ENUM)}. PENDING→APPROVED/REJECTED không phải 1 trong 19 FSM chính
 * thức của docs/03-state-machines.md nên không dùng StateMachineBase — guard 2-outcome thuần ở
 * Service layer.
 */
@Entity
@Table(name = "inventory_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class InventoryAdjustment {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity_adjusted", nullable = false)
    private int quantityAdjusted;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 50)
    private AdjustmentReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InventoryAdjustmentStatus status = InventoryAdjustmentStatus.PENDING;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public InventoryAdjustment(UUID storeId, UUID productId, int quantityAdjusted,
                                AdjustmentReason reason, UUID createdBy) {
        this.storeId = storeId;
        this.productId = productId;
        this.quantityAdjusted = quantityAdjusted;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
