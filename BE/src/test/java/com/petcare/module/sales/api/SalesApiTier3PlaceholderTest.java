package com.petcare.module.sales.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/**
 * Placeholder tầng 3 của sales (T55c, docs/06 §6b hàng 13, 16; docs/adr/0117): method {@code default} ném
 * {@link UnsupportedOperationException} tới khi bản thật ghi đè.
 */
class SalesApiTier3PlaceholderTest {

    private final OrderQueryApi orders = mock(OrderQueryApi.class, CALLS_REAL_METHODS);
    private final CashierShiftQueryApi shifts = mock(CashierShiftQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void countOpenByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> orders.countOpenByBranch(1L), "OrderQueryApi.countOpenByBranch", "T75");
    }

    @Test
    void hasOpenShiftByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> shifts.hasOpenShift(1L), "CashierShiftQueryApi.hasOpenShift", "T75");
    }
}
