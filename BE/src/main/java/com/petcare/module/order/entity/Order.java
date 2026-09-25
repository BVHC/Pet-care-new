package com.petcare.module.order.entity;

import com.petcare.platform.enums.OrderChannel;
import com.petcare.platform.enums.OrderStatus;
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
 * Aggregate Root Module 14 (Order) — docs/06-erd.md §3.6 bảng orders, docs/05-domain-model.md
 * §4.14, FSM-5 (docs/03-state-machines.md §5). Không extend BaseEntity — {@code customer_id} là
 * định danh nghiệp vụ trỏ {@code users(id)} (không phải audit trail chung {@code accounts(id)}),
 * cùng lý do {@code PurchaseRequest} ở Module 13 không extend BaseEntity. {@code status} map
 * {@code @JdbcTypeCode(NAMED_ENUM)} vì {@code order_status_enum} là Postgres native type (V1);
 * {@code channel} map {@code @Enumerated(STRING)} thuần vì chỉ là VARCHAR thường, không có
 * Postgres enum type tương ứng. {@code reservedUntil} (V17) chỉ set cho đơn Online — RULE-14-04;
 * POS luôn NULL vì không giữ chỗ ảo (D-03 instant handover).
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private OrderChannel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "total_refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalRefundedAmount = BigDecimal.ZERO;

    @Column(name = "reserved_until")
    private LocalDateTime reservedUntil;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Order(UUID storeId, UUID customerId, String orderNumber, OrderChannel channel, OrderStatus status) {
        this.storeId = storeId;
        this.customerId = customerId;
        this.orderNumber = orderNumber;
        this.channel = channel;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
