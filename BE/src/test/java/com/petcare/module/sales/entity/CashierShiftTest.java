package com.petcare.module.sales.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CashierShiftTest {

    private static final Instant T0 = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-21T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-21T12:00:00Z");

    private static CashierShift open(boolean afterHours) {
        return CashierShift.open(1L, 2L, afterHours, LocalDate.parse("2026-10-21"), T0);
    }

    @Test
    void openStartsOpenWithFlagsAndNoMoney() {
        CashierShift shift = open(true);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.OPEN);
        assertThat(shift.isAfterHours()).isTrue();
        assertThat(shift.isAutoClosed()).isFalse();
        assertThat(shift.getBusinessDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(shift.getExpectedCash()).isNull();
        assertThat(shift.getCountedCash()).isNull();
        assertThat(shift.getDifference()).isNull();
    }

    @Test
    void closeByCashierStoresCountsAndTotalsWithoutDifference() {
        CashierShift shift = open(false);

        shift.closeByCashier(480_000, new ShiftTotals(500_000, 200_000), T1);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
        assertThat(shift.isAutoClosed()).isFalse();
        assertThat(shift.getExpectedCash()).isEqualTo(500_000L);
        assertThat(shift.getExpectedTransfer()).isEqualTo(200_000L);
        assertThat(shift.getCountedCash()).isEqualTo(480_000L);
        assertThat(shift.getDifference()).isNull();
        assertThat(shift.getClosedAt()).isEqualTo(T1);
    }

    @Test
    void autoCloseLeavesCountedCashNull() {
        CashierShift shift = open(false);

        shift.autoClose(new ShiftTotals(10, 20), T1);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
        assertThat(shift.isAutoClosed()).isTrue();
        assertThat(shift.getCountedCash()).isNull();
        assertThat(shift.getExpectedCash()).isEqualTo(10L);
        assertThat(shift.getExpectedTransfer()).isEqualTo(20L);
    }

    @Test
    void reconcileComputesDifferenceAndOverridesCount() {
        CashierShift shift = open(false);
        shift.closeByCashier(480_000, new ShiftTotals(500_000, 0), T1);

        shift.reconcile(490_000L, "Thiếu 10k", 9L, T2);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.RECONCILED);
        assertThat(shift.getCountedCash()).isEqualTo(490_000L);
        assertThat(shift.getDifference()).isEqualTo(-10_000L);
        assertThat(shift.getReconciledBy()).isEqualTo(9L);
        assertThat(shift.getReconciledAt()).isEqualTo(T2);
        assertThat(shift.getReconcileNote()).isEqualTo("Thiếu 10k");
    }

    @Test
    void reconcileKeepsCashierCountWhenNull() {
        CashierShift shift = open(false);
        shift.closeByCashier(480_000, new ShiftTotals(500_000, 0), T1);

        shift.reconcile(null, "Khớp số lễ tân nhập", 9L, T2);

        assertThat(shift.getCountedCash()).isEqualTo(480_000L);
        assertThat(shift.getDifference()).isEqualTo(-20_000L);
    }
}
