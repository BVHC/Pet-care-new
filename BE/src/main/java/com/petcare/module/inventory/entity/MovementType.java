package com.petcare.module.inventory.entity;

/** Loại biến động tồn ({@code stock_movements.movement_type}, erd §9). */
public enum MovementType {
    RECEIPT,
    RECEIPT_CANCEL,
    SALE,
    VACCINATION,
    VACCINATION_REVERT,
    ADJUSTMENT
}
