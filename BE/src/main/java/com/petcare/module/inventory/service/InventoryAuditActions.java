package com.petcare.module.inventory.service;

/** Mã action audit của module kho (convention 08 §8.3, docs/adr/0042). */
final class InventoryAuditActions {

    /** Hủy phiếu nhập đã xác nhận (BR-KO-04): {@code entity = stock_receipts}, before/after = {@code {status}}, kèm lý do. */
    static final String STOCK_RECEIPT_CANCELLED = "STOCK_RECEIPT_CANCELLED";

    /** Phiếu điều chỉnh tồn (BR-KO-06): {@code entity = stock_adjustments}, after = lý do + các dòng. */
    static final String STOCK_ADJUSTED = "STOCK_ADJUSTED";

    private InventoryAuditActions() {
    }
}
