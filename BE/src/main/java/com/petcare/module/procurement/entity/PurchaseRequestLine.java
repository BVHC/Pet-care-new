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
 * Dòng chi tiết Module 13 — docs/06-erd.md §3.5 bảng purchase_request_lines. Dòng thuần túy, tạo
 * 1 lần lúc {@code CreatePurchaseRequest}, không audit column nào — cùng kiểu
 * {@code stock_transfer_lines}/{@code purchase_order_lines}. {@code recommendedSupplierName}
 * (V16) — RULE-13-01 yêu cầu "nhà cung cấp khuyến nghị" nhưng chỉ là gợi ý tự do, khác hẳn
 * {@link Supplier} thật gắn ở {@link PurchaseOrder#getSupplierId()} (có ràng buộc ACTIVE).
 */
@Entity
@Table(name = "purchase_request_lines")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseRequestLine {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(name = "estimated_unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedUnitPrice;

    @Column(name = "recommended_supplier_name", length = 255)
    private String recommendedSupplierName;

    public PurchaseRequestLine(UUID purchaseRequestId, UUID productId, int requestedQuantity,
                                BigDecimal estimatedUnitPrice, String recommendedSupplierName) {
        this.purchaseRequestId = purchaseRequestId;
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.estimatedUnitPrice = estimatedUnitPrice;
        this.recommendedSupplierName = recommendedSupplierName;
    }
}
