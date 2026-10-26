package com.petcare.module.visit.api;

/** Owner: visit (KB, PrescriptionItem) · BE-1. Caller: sales (TG). Bean: {@code PrescriptionApiService} (docs/adr/0076). */
public interface PrescriptionApi {

    /**
     * BR-BH-04: thiếu tồn lúc thu, dòng thuốc kê đơn chuyển sang mua ngoài: {@code is_external_purchase = true},
     * {@code external_reason = OUT_OF_STOCK_AT_PAYMENT}, gỡ {@code order_line_id}.
     * Sales gọi TRƯỚC khi xóa dòng Order, rồi tự ghi audit.
     * <p>
     * Nghĩa vụ bên gọi (sales): đang trong transaction của mình ({@code MANDATORY}) và không {@code catch} lỗi của hàm
     * này; đã khóa Order và kiểm {@code PENDING}; chỉ gọi cho dòng {@code DRUG} của Order nguồn {@code VISIT} — mọi trường
     * hợp khác (dòng bán lẻ, tồn vẫn đủ) tự trả 400. {@code orderLineId} không ứng với dòng đơn thuốc nào (dòng bán lẻ,
     * gọi lần hai) là lỗi của bên gọi: {@link IllegalStateException}, rollback cả transaction của sales. Nội dung dòng
     * (thuốc, số lượng, liều) không đổi và dòng vẫn in trên đơn.
     */
    void markExternalPurchaseAtPayment(Long orderLineId, Long actorId);
}
