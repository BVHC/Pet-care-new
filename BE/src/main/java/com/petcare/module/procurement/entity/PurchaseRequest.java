package com.petcare.module.procurement.entity;

import com.petcare.platform.enums.PurchaseRequestStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Yêu cầu mua hàng nội bộ Module 13 — docs/06-erd.md §3.5 bảng purchase_requests (RULE-13-01
 * khởi tạo, RULE-13-02 Maker-Checker duyệt/từ chối, RULE-13-03 hủy). Không extend BaseEntity —
 * {@code createdBy}/{@code approvedBy} là cặp định danh Maker-Checker nghiệp vụ trỏ
 * {@code users(id)} (không phải {@code accounts(id)} của audit trail chung), cùng lý do
 * {@code InventoryAdjustment} ở Module 12 không extend BaseEntity. {@code approvedBy} dùng chung
 * cho cả 2 nhánh APPROVED/REJECTED (người quyết định), {@code decidedAt} tương ứng. FSM-12 là FSM
 * thật (docs/03-state-machines.md §12) — transition đi qua
 * {@link com.petcare.module.procurement.fsm.PurchaseRequestTransitionHandler}, guard nghiệp vụ
 * (RULE-ID) luôn chạy trước guard FSM.
 */
@Entity
@Table(name = "purchase_requests")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseRequest {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "request_number", nullable = false, length = 50)
    private String requestNumber;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public PurchaseRequest(String requestNumber, UUID storeId, UUID createdBy) {
        this.requestNumber = requestNumber;
        this.storeId = storeId;
        this.createdBy = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
