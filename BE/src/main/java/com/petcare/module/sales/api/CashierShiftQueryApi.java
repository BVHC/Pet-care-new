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
}
