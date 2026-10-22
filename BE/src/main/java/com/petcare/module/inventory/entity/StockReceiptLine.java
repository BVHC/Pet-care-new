package com.petcare.module.inventory.entity;

import java.time.LocalDate;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Bảng {@code stock_receipt_lines} (erd §9, PART của StockReceipt). {@code stockLotId} gán khi xác nhận (BR-KO-04). */
@Getter
@Entity
@Table(name = "stock_receipt_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReceiptLine extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false)
    private long unitCost;

    @Column(name = "lot_number")
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "stock_lot_id")
    private Long stockLotId;

    public static StockReceiptLine of(Long productId, int quantity, long unitCost, String lotNumber,
            LocalDate expiryDate) {
        StockReceiptLine line = new StockReceiptLine();
        line.productId = productId;
        line.quantity = quantity;
        line.unitCost = unitCost;
        line.lotNumber = lotNumber;
        line.expiryDate = expiryDate;
        return line;
    }

    /** Xác nhận phiếu: gắn dòng với lô đã cộng tồn, để hủy phiếu trừ đúng lô (BR-KO-04). */
    public void assignLot(Long lotId) {
        this.stockLotId = lotId;
    }
}
