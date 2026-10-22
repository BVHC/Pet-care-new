package com.petcare.module.visit.entity;

/**
 * {@code prescription_items.external_reason} (erd §6, CHECK của V1): lý do dòng đơn thuốc là <b>mua ngoài</b> — thiếu
 * tồn lúc kê (BR-KB-03) hoặc lúc thu tiền (BR-BH-04). Entity {@code PrescriptionItem} đến cùng T38; enum có từ T27
 * phần 0 vì schema {@code PrescriptionItem} của visit-v1 đã nằm trong {@code MedicalRecord} (docs/adr/0064).
 */
public enum ExternalPurchaseReason {
    OUT_OF_STOCK_AT_PRESCRIBE,
    OUT_OF_STOCK_AT_PAYMENT
}
