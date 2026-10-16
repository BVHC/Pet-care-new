package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.module.inventory.entity.LockedLot;
import com.petcare.module.inventory.entity.MovementType;
import com.petcare.module.inventory.repository.StockLotDao;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/** StockApi: khóa một câu → chia FEFO → thiếu thì không trừ gì → ghi qua StockLedger đúng thứ tự lô. */
class StockIssueServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final StockLotDao lots = mock(StockLotDao.class);
    private final StockLedger ledger = mock(StockLedger.class);
    private final StockIssueService service = new StockIssueService(lots, ledger, Clock.fixed(NOW, ZoneOffset.UTC));

    private static LockedLot lot(long id, long productId, String expiry, int quantity) {
        return new LockedLot(id, 1L, 5L, productId, "L" + id, expiry == null ? null : LocalDate.parse(expiry), quantity,
                expiry == null);
    }

    @Test
    void saleLocksAllCandidatesOnceThenIssuesInLotIdOrder() {
        when(lots.lockIssuableLots(eq(5L), any(), eq(TODAY))).thenReturn(List.of(lot(1, 20, "2027-01-01", 5),
                lot(2, 10, "2026-12-01", 3), lot(3, 10, "2027-02-01", 10)));

        List<Shortage> shortages = service.issueForSale(77L, 5L, List.of(new SaleLine(10L, 6), new SaleLine(20L, 2)),
                3L);

        assertThat(shortages).isEmpty();
        InOrder order = inOrder(lots, ledger);
        order.verify(lots).lockIssuableLots(5L, Set.of(10L, 20L), TODAY);
        order.verify(ledger).apply(lot(1, 20, "2027-01-01", 5), -2, MovementType.SALE, "orders", 77L, 3L);
        order.verify(ledger).apply(lot(2, 10, "2026-12-01", 3), -3, MovementType.SALE, "orders", 77L, 3L);
        order.verify(ledger).apply(lot(3, 10, "2027-02-01", 10), -3, MovementType.SALE, "orders", 77L, 3L);
    }

    @Test
    void duplicateProductLinesAreMergedBeforeAllocating() {
        when(lots.lockIssuableLots(eq(5L), any(), eq(TODAY))).thenReturn(List.of(lot(2, 10, "2026-12-01", 5)));

        List<Shortage> shortages = service.issueForSale(77L, 5L, List.of(new SaleLine(10L, 3), new SaleLine(10L, 3)),
                3L);

        assertThat(shortages).containsExactly(new Shortage(10L, 6, 5));
        verify(ledger, never()).apply(any(), anyInt(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void anyShortageMeansNothingIsIssuedAndEveryShortLineIsReported() {
        when(lots.lockIssuableLots(eq(5L), any(), eq(TODAY))).thenReturn(List.of(lot(1, 10, "2027-01-01", 100),
                lot(2, 20, "2027-01-01", 1), lot(3, 30, "2025-01-01", 50)));

        List<Shortage> shortages = service.issueForSale(77L, 5L,
                List.of(new SaleLine(30L, 4), new SaleLine(10L, 5), new SaleLine(20L, 2)), 3L);

        assertThat(shortages).containsExactly(new Shortage(20L, 2, 1), new Shortage(30L, 4, 0));
        verify(ledger, never()).apply(any(), anyInt(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void emptySaleTouchesNothing() {
        assertThat(service.issueForSale(77L, 5L, List.of(), 3L)).isEmpty();
        verifyNoInteractions(lots, ledger);
    }

    @Test
    void invalidSaleInputIsAProgrammingError() {
        assertThatThrownBy(() -> service.issueForSale(77L, 5L, List.of(new SaleLine(10L, 0)), 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueForSale(null, 5L, List.of(), 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueForSale(77L, 5L, List.of(new SaleLine(10L, 1)), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lots, ledger);
    }

    @Test
    void lockFefoLotPicksTheEarliestLotThatHoldsEnoughOrFailsWithBrKo05() {
        when(lots.lockIssuableLots(5L, List.of(10L), TODAY)).thenReturn(List.of(lot(1, 10, "2026-12-01", 1),
                lot(2, 10, "2027-01-01", 5)));

        assertThat(service.lockFefoLot(5L, 10L, 2)).isEqualTo(2L);
        assertThatThrownBy(() -> service.lockFefoLot(5L, 10L, 6)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KO-05");
        assertThatThrownBy(() -> service.lockFefoLot(5L, 10L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vaccinationIssuesFromTheLockedLotButNeverFromAnExpiredOne() {
        when(lots.lockLots(List.of(2L))).thenReturn(List.of(lot(2, 10, "2027-01-01", 5)));
        when(lots.lockLots(List.of(3L))).thenReturn(List.of(lot(3, 10, "2026-10-15", 5)));
        when(lots.lockLots(List.of(4L))).thenReturn(List.of());

        service.issueForVaccination(2L, 1, 900L, 3L);
        verify(ledger).apply(lot(2, 10, "2027-01-01", 5), -1, MovementType.VACCINATION, "vaccinations", 900L, 3L);

        assertThatThrownBy(() -> service.issueForVaccination(3L, 1, 901L, 3L))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KO-05");
        assertThatThrownBy(() -> service.issueForVaccination(4L, 1, 902L, 3L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revertGivesTheQuantityBackToTheSameLotEvenIfItExpiredMeanwhile() {
        when(lots.lockLots(List.of(3L))).thenReturn(List.of(lot(3, 10, "2026-10-15", 0)));

        service.revertVaccination(3L, 1, 900L, 3L);

        verify(ledger).apply(lot(3, 10, "2026-10-15", 0), 1, MovementType.VACCINATION_REVERT, "vaccinations", 900L,
                3L);
    }

    @Test
    void everyMethodJoinsTheCallersTransaction() {
        for (Method method : StockApi.class.getMethods()) {
            try {
                Method impl = StockIssueService.class.getMethod(method.getName(), method.getParameterTypes());
                assertThat(impl.getAnnotation(Transactional.class).propagation()).as(method.getName())
                        .isEqualTo(Propagation.MANDATORY);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    }
}
