package com.petcare.module.sales.api;

/** Owner: sales (TG) · BE-2. Caller: identity (QT). */
public interface CashierShiftQueryApi {

    /** Lễ tân đang có ca OPEN → chặn vô hiệu hóa (BR-QT-08, Tài khoản#5). */
    boolean hasOpenShift(Long cashierAccountId);
}
