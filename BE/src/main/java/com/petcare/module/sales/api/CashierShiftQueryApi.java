package com.petcare.module.sales.api;

import java.util.Optional;

/** Owner: sales (TG) · BE-2. Caller: identity (QT). */
public interface CashierShiftQueryApi {

    /** Ca {@code OPEN} của một lễ tân (tối đa một ca, BR-TG-05). */
    record OpenShift(Long shiftId, Long branchId) {}

    /**
     * Ca {@code OPEN} của lễ tân, nếu có: chặn vô hiệu hóa / điều chuyển (BR-QT-08, Tài khoản#5) và liệt kê khi khóa
     * (BR-QT-11). Đọc thuần, không khóa, chạy trong transaction của bên gọi (docs/adr/0031, 0035). Thay
     * {@code hasOpenShift} từ 2026-10-12 (khóa tài khoản cần id ca và chi nhánh).
     */
    Optional<OpenShift> findOpenShift(Long cashierAccountId);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 13): placeholder dạng default — ném UnsupportedOperationException
    // tới khi CashierShiftLookupService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /**
     * BR-CN-07, BR-CN-08 (UC13): chi nhánh còn ca {@code OPEN} (của lễ tân nào cũng được) thì chặn tạm ngừng / đóng cửa.
     * <b>Không</b> phải {@code hasOpenShift(cashierAccountId)} đã bỏ ngày 12/10 (thay bằng {@link #findOpenShift}): tham
     * số là id chi nhánh. Bản thật trước 7/12 (T75).
     */
    default boolean hasOpenShift(Long branchId) {
        throw placeholder("hasOpenShift", "T75");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("CashierShiftQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
