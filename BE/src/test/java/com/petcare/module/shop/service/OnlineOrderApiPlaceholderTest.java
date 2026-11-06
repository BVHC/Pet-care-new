package com.petcare.module.shop.service;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;

import org.junit.jupiter.api.Test;

/** Placeholder của {@code OnlineOrderApi} (T55c, docs/06 §6b hàng 4, docs/adr/0117): ném lỗi, không đổi trạng thái đơn. */
class OnlineOrderApiPlaceholderTest {

    private final OnlineOrderApiPlaceholder placeholder = new OnlineOrderApiPlaceholder();

    @Test
    void markPaidThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.markPaid(1L, "TXN-1"), "OnlineOrderApi.markPaid", "T65");
    }

    @Test
    void markPaymentCancelledThrowsUntilImplemented() {
        assertPlaceholder(() -> placeholder.markPaymentCancelled(1L), "OnlineOrderApi.markPaymentCancelled", "T65");
    }
}
