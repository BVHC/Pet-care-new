package com.petcare.module.visit.api;

/** Owner: visit (KB, PrescriptionItem) · BE-1. Caller: sales (TG). Bean: {@code PrescriptionApiService} (docs/adr/0076). */
public interface PrescriptionApi {

    /** Dòng đơn thuốc của một lượt, đủ để y lệnh nội trú quyết định có liên kết được không (BR-LT-15). */
    record PrescriptionItemRef(Long itemId, boolean externalPurchase) {}

    /**
     * Nội trú (docs/adr/0149 mục 28): khóa dòng đơn thuốc {@code itemId} của lượt {@code visitId} {@code FOR KEY SHARE}
     * rồi trả về; rỗng nếu dòng không tồn tại hoặc thuộc lượt khác. Khóa giữ tới hết transaction của bên gọi, nên xóa dòng
     * đơn (visit-v1 #17) chờ bên ra y lệnh xong, và ngược lại. Bên gọi (inpatient) tự phân quyền, tự quyết định dòng mua
     * ngoài có dùng được không; method này không kiểm.
     * <p>
     * Chạy trong transaction của bên gọi ({@code MANDATORY}); bên gọi không bắt ngoại lệ của nó.
     *
     * @throws IllegalArgumentException {@code itemId} hoặc {@code visitId} null (lỗi lập trình của bên gọi)
     */
    java.util.Optional<PrescriptionItemRef> findItemOfVisitForKeyShare(Long itemId, Long visitId);

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
