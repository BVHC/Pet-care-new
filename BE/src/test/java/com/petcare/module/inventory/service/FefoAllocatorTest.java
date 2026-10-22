package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.service.FefoAllocator.Take;

/** BR-KO-05: hết hạn trước xuất trước, không bao giờ xuất lô hết hạn; thiếu thì không chia một phần. */
class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static LockedLot lot(long id, String expiry, int quantity) {
        return new LockedLot(id, 1L, 1L, 9L, "L" + id, expiry == null ? null : LocalDate.parse(expiry), quantity,
                expiry == null);
    }

    private static List<String> summary(Optional<List<Take>> takes) {
        return takes.orElseThrow().stream().map(t -> t.lot().id() + ":" + t.quantity()).toList();
    }

    @Test
    void takesFromTheEarliestExpiryFirstRegardlessOfInputOrder() {
        List<LockedLot> lots = List.of(lot(1, "2027-06-01", 10), lot(2, "2026-12-01", 10), lot(3, "2027-01-01", 10));

        assertThat(summary(FefoAllocator.allocate(lots, 8, TODAY))).containsExactly("2:8");
    }

    @Test
    void spillsOverToTheNextLotsInOrderAndTotalsExactly() {
        List<LockedLot> lots = List.of(lot(1, "2027-06-01", 10), lot(2, "2026-12-01", 5), lot(3, "2027-01-01", 4));

        assertThat(summary(FefoAllocator.allocate(lots, 12, TODAY))).containsExactly("2:5", "3:4", "1:3");
    }

    @Test
    void neverUsesAnExpiredLotButTodayIsStillUsable() {
        List<LockedLot> lots = List.of(lot(1, "2026-10-15", 100), lot(2, "2026-10-16", 3), lot(3, "2027-01-01", 5));

        assertThat(summary(FefoAllocator.allocate(lots, 8, TODAY))).containsExactly("2:3", "3:5");
        assertThat(FefoAllocator.allocate(lots, 9, TODAY)).as("100 ở lô hết hạn không được tính").isEmpty();
        assertThat(FefoAllocator.available(lots, TODAY)).isEqualTo(8);
    }

    @Test
    void defaultLotWithoutExpiryComesAfterDatedLots() {
        List<LockedLot> lots = List.of(lot(1, null, 10), lot(2, "2027-01-01", 3));

        assertThat(summary(FefoAllocator.allocate(lots, 5, TODAY))).containsExactly("2:3", "1:2");
    }

    @Test
    void sameExpiryTakesTheSmallerIdFirst() {
        List<LockedLot> lots = List.of(lot(7, "2027-01-01", 5), lot(3, "2027-01-01", 5));

        assertThat(summary(FefoAllocator.allocate(lots, 6, TODAY))).containsExactly("3:5", "7:1");
    }

    @Test
    void shortageDoesNotSplitAndEmptyLotsAreIgnored() {
        List<LockedLot> lots = List.of(lot(1, "2027-01-01", 0), lot(2, "2027-02-01", 4));

        assertThat(FefoAllocator.allocate(lots, 5, TODAY)).isEmpty();
        assertThat(summary(FefoAllocator.allocate(lots, 4, TODAY))).containsExactly("2:4");
        assertThat(FefoAllocator.allocate(List.of(), 1, TODAY)).isEmpty();
    }

    @Test
    void firstLotWithPicksTheEarliestLotThatAloneHoldsEnough() {
        List<LockedLot> lots = List.of(lot(1, "2026-12-01", 2), lot(2, "2027-01-01", 5), lot(3, "2026-10-01", 50));

        assertThat(FefoAllocator.firstLotWith(lots, 3, TODAY)).map(LockedLot::id).contains(2L);
        assertThat(FefoAllocator.firstLotWith(lots, 51, TODAY)).isEmpty();
    }

    @Test
    void nonPositiveQuantityIsAProgrammingError() {
        assertThatThrownBy(() -> FefoAllocator.allocate(List.of(), 0, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
