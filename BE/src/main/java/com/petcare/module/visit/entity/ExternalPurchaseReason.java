package com.petcare.module.visit.entity;

/**
 * {@code prescription_items.external_reason} (erd §6, CHECK của V1): lý do dòng đơn thuốc là <b>mua ngoài</b> — thiếu
 * tồn lúc kê (BR-KB-03, ghi ở T38 phần 1) hoặc lúc thu tiền (BR-BH-04, {@code PrescriptionApi}, docs/adr/0076).
 */
public enum ExternalPurchaseReason {
    OUT_OF_STOCK_AT_PRESCRIBE,
    OUT_OF_STOCK_AT_PAYMENT
}
