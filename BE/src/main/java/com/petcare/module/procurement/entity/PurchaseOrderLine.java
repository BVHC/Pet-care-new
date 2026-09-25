package com.petcare.module.procurement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dòng chi tiết Module 13 — docs/06-erd.md §3.5 bảng purchase_order_lines. Copy 1:1 từ
 * {@link PurchaseRequestLine} lúc {@code CreatePurchaseOrder} (không nhận lại từ client — đơn
 * giản, chống giả mạo). {@code receivedQuantity} thuộc phạm vi RULE-13-05→08 (ngoài scope task
 * này), giữ mặc định 0.
 */
@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderLine {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "ordered_quantity", nullable = false)
    private int orderedQuantity;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity = 0;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    public PurchaseOrderLine(UUID purchaseOrderId, UUID productId, int orderedQuantity, BigDecimal unitPrice) {
        this.purchaseOrderId = purchaseOrderId;
        this.productId = productId;
        this.orderedQuantity = orderedQuantity;
        this.unitPrice = unitPrice;
    }
}
