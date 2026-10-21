package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.ReconcileShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.entity.ShiftTotals;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.ShiftAccessRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** Đối soát ca (sales-v1 #21, UC72; Ca thu ngân#6; BR-TG-04, 05, BR-QT-15; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class ShiftReconcileServiceTest {

    private static final long MANAGER = 4L;
    private static final long SHIFT_ID = 40L;
    private static final long BRANCH = 3L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00.987654321Z");

    @Mock
    CashierShiftRepository shifts;
    @Mock
    AuditRecorder audit;
    @Mock
    CashierShiftAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;
    @Mock
    ShiftAccessRow access;

    ShiftReconcileService service;
    CashierShiftResponse response;

    @BeforeEach
    void setUp() {
        service = new ShiftReconcileService(shifts, new CashierShiftTransitionHandler(), audit, assembler,
                branchScope, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(MANAGER);
        lenient().when(access.getBranchId()).thenReturn(BRANCH);
        lenient().when(shifts.findAccessRowById(SHIFT_ID)).thenReturn(Optional.of(access));
        response = mock(CashierShiftResponse.class);
        lenient().when(assembler.toResponse(any(CashierShift.class))).thenReturn(response);
    }

    private static CashierShift closedByCashier(long counted, long expectedCash) {
        CashierShift shift = CashierShift.open(BRANCH, 7L, false, LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T01:00:00Z"));
        shift.closeByCashier(counted, new ShiftTotals(expectedCash, 0), Instant.parse("2026-10-21T11:00:00Z"));
        return shift;
    }

    private static CashierShift autoClosed(long expectedCash) {
        CashierShift shift = CashierShift.open(BRANCH, 7L, false, LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T01:00:00Z"));
        shift.autoClose(new ShiftTotals(expectedCash, 0), Instant.parse("2026-10-21T11:00:00Z"));
        return shift;
    }

    @Test
    void reconcilesAClosedShiftAndAuditsAfterTheWrite() {
        CashierShift shift = closedByCashier(480_000, 500_000);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        assertThat(service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(null, "  Thiếu 20k  ")))
                .isSameAs(response);

        InOrder order = inOrder(branchScope, shifts, audit);
        order.verify(shifts).findAccessRowById(SHIFT_ID);
        order.verify(branchScope).check(BRANCH);
        order.verify(shifts).findByIdForUpdate(SHIFT_ID);
        order.verify(shifts).saveAndFlush(shift);
        order.verify(audit).record(any(AuditEntry.class));
        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.RECONCILED);
        assertThat(shift.getDifference()).isEqualTo(-20_000L);
        assertThat(shift.getReconcileNote()).isEqualTo("Thiếu 20k");
        assertThat(shift.getReconciledBy()).isEqualTo(MANAGER);
        assertThat(shift.getReconciledAt()).isEqualTo(Instant.parse("2026-10-21T12:00:00.987654Z"));
    }

    @Test
    void overridingTheCountIsAuditedWithBothValues() {
        CashierShift shift = closedByCashier(480_000, 500_000);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(500_000L, "Đếm lại đủ"));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("CASHIER_SHIFT_RECONCILED");
        assertThat(entry.getValue().before()).hasToString("{status=CLOSED, countedCash=480000, autoClosed=false}");
        assertThat(entry.getValue().after()).hasToString(
                "{status=RECONCILED, countedCash=500000, expectedCash=500000, difference=0}");
        assertThat(entry.getValue().reason()).isEqualTo("Đếm lại đủ");
    }

    @Test
    void autoClosedShiftWithoutACountIsBrTg05() {
        CashierShift shift = autoClosed(100_000);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(null, "ok")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-05");

        verify(shifts, never()).saveAndFlush(any());
        verify(audit, never()).record(any());
        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
    }

    @Test
    void autoClosedShiftWithACountReconciles() {
        CashierShift shift = autoClosed(100_000);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift));

        service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(90_000L, "Nhập hộ"));

        assertThat(shift.getCountedCash()).isEqualTo(90_000L);
        assertThat(shift.getDifference()).isEqualTo(-10_000L);
    }

    @Test
    void openOrReconciledShiftIs409AndWritesNothing() {
        CashierShift open = CashierShift.open(BRANCH, 7L, false, LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T01:00:00Z"));
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(1L, "x")))
                .isInstanceOf(InvalidStateTransitionException.class);

        CashierShift done = closedByCashier(1, 1);
        done.reconcile(null, "x", MANAGER, NOW);
        when(shifts.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(1L, "x")))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(shifts, never()).saveAndFlush(any());
        verify(audit, never()).record(any());
    }

    @Test
    void anotherBranchIs403BeforeTheLock() {
        doThrow(new AccessDeniedScopeException("branch:3", "branch:4")).when(branchScope).check(BRANCH);

        assertThatThrownBy(() -> service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(1L, "x")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(shifts, never()).findByIdForUpdate(any());
    }

    @Test
    void missingShiftIs404() {
        when(shifts.findAccessRowById(SHIFT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reconcileCashierShift(SHIFT_ID, new ReconcileShiftRequest(1L, "x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
