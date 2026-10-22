package com.petcare.module.inventory.entity;

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

/** Bảng {@code stock_adjustment_lines} (erd §9, PART của StockAdjustment): âm = giảm, dương = tăng, khác 0. */
@Getter
@Entity
@Table(name = "stock_adjustment_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAdjustmentLine extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_lot_id", nullable = false, updatable = false)
    private Long stockLotId;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;

    public static StockAdjustmentLine of(Long stockLotId, int quantityDelta) {
        StockAdjustmentLine line = new StockAdjustmentLine();
        line.stockLotId = stockLotId;
        line.quantityDelta = quantityDelta;
        return line;
    }
}
