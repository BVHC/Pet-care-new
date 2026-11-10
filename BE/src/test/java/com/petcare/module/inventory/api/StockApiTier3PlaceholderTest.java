package com.petcare.module.inventory.api;

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
 * Placeholder tầng 3 của inventory (T55c, docs/06 §6b hàng 3, 8, 12, 13, 16; docs/adr/0117): method {@code default} ném
 * {@link UnsupportedOperationException} tới khi bản thật ghi đè. {@code CALLS_REAL_METHODS} gọi đúng thân {@code default}.
 */
class StockApiTier3PlaceholderTest {

    private final StockApi stock = mock(StockApi.class, CALLS_REAL_METHODS);
    private final StockQueryApi query = mock(StockQueryApi.class, CALLS_REAL_METHODS);
    private final StockApi.OrderRef ref = new StockApi.OrderRef(7L, 8L);
    private final List<StockApi.SaleLine> lines = List.of(new StockApi.SaleLine(1L, 2));
    private final List<StockApi.TransferredLot> lots =
            List.of(new StockApi.TransferredLot(1L, 5L, 2, "L1", LocalDate.of(2027, 1, 1)));

    @Test
    void reserveThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.reserve(1L, ref, lines), "StockApi.reserve", "T63");
    }

    @Test
    void releaseThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.release(ref), "StockApi.release", "T63");
    }

    @Test
    void consumeReservationThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.consumeReservation(ref, 1L), "StockApi.consumeReservation", "T63");
    }

    @Test
    void restockThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.restock(ref, 1L), "StockApi.restock", "T63");
    }

    @Test
    void restockToOriginalLotThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.restockToOriginalLot(1L, 1, 1L), "StockApi.restockToOriginalLot", "T73");
    }

    @Test
    void discardReturnedThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.discardReturned(1L, 1, 1L), "StockApi.discardReturned", "T73");
    }

    @Test
    void transferOutThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.transferOut(1L, 2L, lines, 1L), "StockApi.transferOut", "T74");
    }

    @Test
    void transferInThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.transferIn(1L, 2L, lots, 1L), "StockApi.transferIn", "T74");
    }

    @Test
    void transferCancelThrowsUntilImplemented() {
        assertPlaceholder(() -> stock.transferCancel(1L, 2L, lots, 1L), "StockApi.transferCancel", "T74");
    }

    @Test
    void findAvailableQuantityThrowsUntilImplemented() {
        assertPlaceholder(() -> query.findAvailableQuantity(1L, Set.of(1L)), "StockQueryApi.findAvailableQuantity", "T63");
    }

    @Test
    void findMovementsOfLotThrowsUntilImplemented() {
        assertPlaceholder(() -> query.findMovementsOfLot(1L), "StockQueryApi.findMovementsOfLot", "T74");
    }

    @Test
    void sumQuantityByBranchThrowsUntilImplemented() {
        assertPlaceholder(() -> query.sumQuantityByBranch(1L), "StockQueryApi.sumQuantityByBranch", "T75");
    }

    /** Hợp đồng dữ liệu: các record mang đúng trường mà 08 §B, §E, 07 I-5 mô tả. */
    @Test
    void recordsCarryTheFieldsOfTheContract() {
        assertThat(ref.onlineOrderId()).isEqualTo(7L);
        assertThat(ref.orderId()).isEqualTo(8L);

        StockApi.TransferredLot lot = lots.get(0);
        assertThat(lot.productId()).isEqualTo(1L);
        assertThat(lot.sourceLotId()).isEqualTo(5L);
        assertThat(lot.quantity()).isEqualTo(2);
        assertThat(lot.lotNumber()).isEqualTo("L1");
        assertThat(lot.expiryDate()).isEqualTo(LocalDate.of(2027, 1, 1));

        StockQueryApi.LotMovement movement = new StockQueryApi.LotMovement(1L, "TRANSFER_OUT", -2, 8, "stock_transfers", 3L, 4L,
                Instant.parse("2026-11-06T03:00:00Z"));
        assertThat(movement.movementType()).isEqualTo("TRANSFER_OUT");
        assertThat(movement.sourceType()).isEqualTo("stock_transfers");
        assertThat(new StockQueryApi.StockTotals(3, 40).totalQuantity()).isEqualTo(40);
    }
}
