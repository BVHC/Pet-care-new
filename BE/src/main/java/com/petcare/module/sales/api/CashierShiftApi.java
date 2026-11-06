package com.petcare.module.sales.api;

/**
 * Owner: sales (TG) · BE-2. Caller: returns (UC69). Ghi vào ca thu ngân; đọc ca dùng {@link CashierShiftQueryApi}.
 * Mới ở T55c (docs/adr/0117, docs/06 §6b hàng 8): trước khi có bản thật, {@code CashierShiftApiPlaceholder} ném
 * {@link UnsupportedOperationException}.
 */
public interface CashierShiftApi {

    /**
     * BR-TG-13, UC72 (08 §D): ghi một khoản hoàn tiền tại quầy vào ca {@code OPEN} {@code shiftId} để đối soát ca trừ
     * hoàn tiền. {@code method} chỉ {@link CollectMethod#CASH} hoặc {@link CollectMethod#TRANSFER} (hoàn qua cổng không
     * vào ca). {@code MANDATORY}: chạy trong transaction của bên gọi (06 §1). Bản thật trước 30/11 (T73).
     */
    void recordRefund(Long shiftId, CollectMethod method, long amount);
}
