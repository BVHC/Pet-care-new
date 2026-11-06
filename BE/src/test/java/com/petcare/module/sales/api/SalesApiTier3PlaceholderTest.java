package com.petcare.module.sales.api;

import static com.petcare.module.PlaceholderAssert.assertPlaceholder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Placeholder tầng 3 của sales (T55c, docs/06 §6b hàng 8, 13, 16; docs/adr/0117): method {@code default} ném
 * {@link UnsupportedOperationException} tới khi bản thật ghi đè.
 */
class SalesApiTier3PlaceholderTest {

    private final OrderQueryApi orders = mock(OrderQueryApi.class, CALLS_REAL_METHODS);
    private final CashierShiftQueryApi shifts = mock(CashierShiftQueryApi.class, CALLS_REAL_METHODS);

    @Test
    void findLinesForReturnThrowsUntilImplemented() {
        assertPlaceholder(() -> orders.findLinesForReturn(1L), "OrderQueryApi.findLinesForReturn", "T73");
    }

    @Test
    void findLastPurchaseDatesThrowsUntilImplemented() {
        assertPlaceholder(() -> orders.findLastPurchaseDates(1L, Set.of(1L)), "OrderQueryApi.findLastPurchaseDates", "T78");
    }

    @Test
    void sumSoldUnitsByProductThrowsUntilImplemented() {
        assertPlaceholder(() -> orders.sumSoldUnitsByProduct(LocalDate.of(2026, 8, 1)),
                "OrderQueryApi.sumSoldUnitsByProduct", "T78");
    }

    @Test
    void countOpenByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> orders.countOpenByBranch(1L), "OrderQueryApi.countOpenByBranch", "T75");
    }

    @Test
    void hasOpenShiftByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> shifts.hasOpenShift(1L), "CashierShiftQueryApi.hasOpenShift", "T75");
    }

    /** Hình thức thu của 08 §D: quầy CASH / TRANSFER, đơn online GATEWAY. */
    @Test
    void collectMethodHasTheThreeMethodsOfRefundsMethod() {
        assertThat(CollectMethod.values()).containsExactly(CollectMethod.CASH, CollectMethod.TRANSFER, CollectMethod.GATEWAY);
    }

    @Test
    void returnableOrderCarriesLinesWithReturnedAndPendingQuantities() {
        OrderQueryApi.ReturnableLine line = new OrderQueryApi.ReturnableLine(10L, 3L, "Thức ăn", 4, 50_000, 1, 2);
        OrderQueryApi.ReturnableOrder order = new OrderQueryApi.ReturnableOrder(5L, 1L, 2L, OrderSource.RETAIL,
                Instant.parse("2026-11-06T03:00:00Z"), CollectMethod.CASH, List.of(line));

        assertThat(order.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity() - l.returnedQuantity() - l.pendingReturnQuantity()).isEqualTo(1);
            assertThat(l.unitPrice()).isEqualTo(50_000L);
        });
        assertThat(order.collectMethod()).isEqualTo(CollectMethod.CASH);
    }
}
