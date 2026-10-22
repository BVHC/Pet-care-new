package com.petcare.module.inventory.entity;

/** Trạng thái phiếu nhập kho ({@code stock_receipts.status}, docs/03 §9): DRAFT → CONFIRMED | CANCELLED, CONFIRMED → CANCELLED. */
public enum StockReceiptStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
