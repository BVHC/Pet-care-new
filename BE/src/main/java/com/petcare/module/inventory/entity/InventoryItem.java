package com.petcare.module.inventory.entity;

import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Aggregate Root Module 12 (Inventory) — docs/06-erd.md §3.5 bảng inventory_items,
 * docs/05-domain-model.md §4.12. Rollup tổng tồn kho theo (storeId, productId): dùng cho
 * TrackInventory + optimistic-lock overselling guard (RULE-12-05). Chi tiết theo lô (FEFO,
 * RULE-12-11) nằm ở {@link InventoryBatch}, không trùng vai trò với entity này.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
public class InventoryItem extends BaseEntity {

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity_physical", nullable = false)
    private int quantityPhysical = 0;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved = 0;

    @Column(name = "quantity_available", nullable = false)
    private int quantityAvailable = 0;

    @Column(name = "min_stock_level", nullable = false)
    private int minStockLevel = 5;

    public InventoryItem(UUID storeId, UUID productId) {
        this.storeId = storeId;
        this.productId = productId;
    }

    public boolean isLowStock() {
        return quantityAvailable <= minStockLevel;
    }
}
