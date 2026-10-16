package com.petcare.module.inventory.entity;

/** Lý do điều chỉnh tồn ({@code stock_adjustments.reason}, BR-KO-06): {@code OTHER} bắt buộc có ghi chú. */
public enum StockAdjustmentReason {
    DAMAGED,
    EXPIRED,
    COUNT_MISMATCH,
    OTHER
}
