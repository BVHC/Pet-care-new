package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.CloseShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.entity.ShiftTotals;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.ShiftAccessRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** Chốt ca thu ngân (sales-v1 #18; Ca thu ngân#3; BR-TG-05; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class CashierShiftCloseServiceTest {

    private static final long ME = 7L;
    private static final long SHIFT_ID = 40L;
    private static final Instant NOW = Instant.parse("2026-10-21T11:00:00.123456789Z");

    @Mock
    CashierShiftRepository shifts;
    @Mock
    ShiftTotalsReader totals;
    @Mock
    CashierShiftAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;
    @Mock
    ShiftAccessRow access;

    CashierShiftCloseService service;
    CashierShiftResponse response;

    @BeforeEach
    void setUp() {
        service = new CashierShiftCloseService(shifts, totals, new CashierShiftTransitionHandler(), assembler,
                branchScope, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(access.getCashierId()).thenReturn(ME);
        lenient().when(shifts.findAccessRowById(SHIFT_ID)).thenReturn(Optional.of(access));
        lenient().when(totals.read(SHIFT_ID)).thenReturn(new ShiftTotals(150_000, 30_000));
        response = mock(CashierShiftResponse.class);
        lenient().when(assembler.toResponse(any(CashierShift.class))).thenReturn(response);
    }

    private CashierShift openShift() {
        return CashierShift.open(3L, ME, false, java.time.LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T01:00:00Z"));
    }

    @Test
    void closesOwnShiftWithTotalsReadAfterTheLock() {
        CashierShift shift = openShift();
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        assertThat(service.closeCashierShift(SHIFT_ID, new CloseShiftRequest(480_000L))).isSameAs(response);

        InOrder order = inOrder(shifts, totals);
        order.verify(shifts).findAccessRowById(SHIFT_ID);
        order.verify(shifts).findByIdForUpdate(SHIFT_ID);
        order.verify(totals).read(SHIFT_ID);
        order.verify(shifts).saveAndFlush(shift);
        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
        assertThat(shift.getExpectedCash()).isEqualTo(150_000L);
        assertThat(shift.getExpectedTransfer()).isEqualTo(30_000L);
        assertThat(shift.getCountedCash()).isEqualTo(480_000L);
        assertThat(shift.getClosedAt()).isEqualTo(Instant.parse("2026-10-21T11:00:00.123456Z"));
        assertThat(shift.isAutoClosed()).isFalse();
    }

    @Test
    void anotherCashiersShiftIs403BeforeTheLock() {
        when(access.getCashierId()).thenReturn(ME + 1);

        assertThatThrownBy(() -> service.closeCashierShift(SHIFT_ID, new CloseShiftRequest(1L)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(shifts, never()).findByIdForUpdate(any());
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void missingShiftIs404() {
        when(shifts.findAccessRowById(SHIFT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeCashierShift(SHIFT_ID, new CloseShiftRequest(1L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shiftThatIsNotOpenIs409AndNothingIsRead() {
        CashierShift shift = openShift();
        shift.closeByCashier(1, new ShiftTotals(0, 0), NOW);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> service.closeCashierShift(SHIFT_ID, new CloseShiftRequest(1L)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(totals, never()).read(any());
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void shiftWithoutPaymentsClosesWithZeroTotals() {
        CashierShift shift = openShift();
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));
        when(totals.read(SHIFT_ID)).thenReturn(new ShiftTotals(0, 0));

        service.closeCashierShift(SHIFT_ID, new CloseShiftRequest(0L));

        assertThat(shift.getExpectedCash()).isZero();
        assertThat(shift.getExpectedTransfer()).isZero();
    }
}
