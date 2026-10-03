package com.petcare.module.visit.api;

/** Owner: visit (KB, PrescriptionItem) · BE-1. Caller: sales (TG). */
public interface PrescriptionApi {

    /**
     * BR-BH-04: thiếu tồn lúc thu, dòng thuốc kê đơn chuyển sang mua ngoài: {@code is_external_purchase = true},
     * {@code external_reason = OUT_OF_STOCK_AT_PAYMENT}, gỡ {@code order_line_id}.
     * Sales gọi TRƯỚC khi xóa dòng Order, rồi tự ghi audit.
     */
    void markExternalPurchaseAtPayment(Long orderLineId, Long actorId);
}
