package com.petcare.module.sales.service;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;

import org.junit.jupiter.api.Test;

import com.petcare.module.sales.api.CollectMethod;

/** Placeholder của {@code CashierShiftApi} (T55c, docs/06 §6b hàng 8, docs/adr/0117): ném lỗi, không ghi gì vào ca. */
class CashierShiftApiPlaceholderTest {

    private final CashierShiftApiPlaceholder placeholder = new CashierShiftApiPlaceholder();

    @Test
    void recordRefundThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.recordRefund(1L, CollectMethod.CASH, 90_000L), "CashierShiftApi.recordRefund", "T73");
    }
}
