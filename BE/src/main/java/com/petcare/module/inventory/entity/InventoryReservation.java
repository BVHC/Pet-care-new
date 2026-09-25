package com.petcare.module.inventory.entity;

import com.petcare.platform.enums.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Giữ chỗ tồn kho 15 phút cho đơn Online — docs/06-erd.md §3.5 bảng inventory_reservations,
 * RULE-14-04 (Module 14 Order). Sống trong module Inventory (không phải Order) vì vòng đời của
 * nó gắn chặt với optimistic-lock trên {@link InventoryItem} (reserve/release luôn đi kèm
 * mutation quantity_reserved/quantity_available của rollup) — Order chỉ gọi qua
 * {@code InventoryItemService#reserveStock}/{@code releaseReservation}, không đụng entity/
 * repository này trực tiếp (đúng "module không import entity/repository module khác"). Plain
 * entity — không BaseEntity, {@code status} là VARCHAR thường (không Postgres native enum), cùng
 * tiền lệ {@code InventoryAdjustment.status}.
 */
@Entity
@Table(name = "inventory_reservations")
@Getter
@Setter
@NoArgsConstructor
public class InventoryReservation {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReservationStatus status = ReservationStatus.HELD;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public InventoryReservation(UUID orderId, UUID inventoryItemId, int quantity, LocalDateTime expiresAt) {
        this.orderId = orderId;
        this.inventoryItemId = inventoryItemId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }
}
