package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.platform.exception.BusinessRuleViolationException;

/** BR-KO-01: tồn không âm, không tràn; mỗi lần đổi số dư có đúng một movement với số dư sau. */
class StockLedgerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    private final StockLotDao lots = mock(StockLotDao.class);
    private final StockLedger ledger = new StockLedger(lots, Clock.fixed(NOW, ZoneOffset.UTC));

    private static LockedLot lot(int quantity) {
        return new LockedLot(5L, 2L, 1L, 9L, "L1", LocalDate.of(2027, 1, 1), quantity, false);
    }

    @Test
    void writesTheBalanceThenTheMovementAndReturnsTheNewLot() {
        LockedLot result = ledger.apply(lot(10), -4, MovementType.SALE, "orders", 77L, 3L);

        assertThat(result.quantity()).isEqualTo(6);
        InOrder order = inOrder(lots);
        order.verify(lots).updateQuantity(5L, 6, NOW);
        order.verify(lots).insertMovement(5L, MovementType.SALE, -4, 6, "orders", 77L, 3L, NOW);
    }

    @Test
    void drainingToZeroIsAllowedButBelowZeroIsNot() {
        assertThat(ledger.apply(lot(4), -4, MovementType.SALE, "orders", 1L, 3L).quantity()).isZero();

        assertThatThrownBy(() -> ledger.apply(lot(3), -4, MovementType.SALE, "orders", 1L, 3L))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-01");
    }

    @Test
    void overflowIsRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> ledger.apply(lot(Integer.MAX_VALUE), 1, MovementType.RECEIPT, "stock_receipts", 1L, 3L))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-01");
        verify(lots, never()).updateQuantity(anyLong(), anyInt(), any());
    }

    @Test
    void zeroDeltaIsAProgrammingError() {
        assertThatThrownBy(() -> ledger.apply(lot(1), 0, MovementType.ADJUSTMENT, "stock_adjustments", 1L, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lots);
    }

    @Test
    void joinsTheCallersTransaction() throws Exception {
        Method apply = StockLedger.class.getMethod("apply", LockedLot.class, int.class, MovementType.class,
                String.class, long.class, long.class);

        assertThat(apply.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
