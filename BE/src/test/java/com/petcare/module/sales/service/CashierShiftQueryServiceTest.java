package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.repository.CashierShiftListRepository;
import com.petcare.module.sales.repository.CashierShiftListRepository.Filter;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** Danh sách và chi tiết ca thu ngân (sales-v1 #19, #20; BR-TG-05; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class CashierShiftQueryServiceTest {

    private static final long ME = 7L;
    private static final long BRANCH = 3L;

    @Mock
    CashierShiftRepository shifts;
    @Mock
    CashierShiftListRepository lists;
    @Mock
    CashierShiftAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;

    CashierShiftQueryService service;

    @BeforeEach
    void setUp() {
        service = new CashierShiftQueryService(shifts, lists, assembler, branchScope);
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
    }

    private static CashierShift shift(long cashier, long branch) {
        return CashierShift.open(branch, cashier, false, LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T01:00:00Z"));
    }

    @Test
    void listIsScopedToTheManagersBranchBeforeAnyRead() {
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        when(lists.count(any())).thenReturn(0L);

        PageResponse<CashierShiftResponse> page = service.listCashierShifts(null, null, null, 0, 20);

        InOrder order = inOrder(branchScope, lists);
        order.verify(branchScope).resolve(null);
        order.verify(lists).count(any());
        assertThat(page.content()).isEmpty();
        verify(lists, never()).findPageIds(any(), anyLong(), anyInt());
    }

    @Test
    void listPassesTheFiltersAndClampsPaging() {
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        when(lists.count(any())).thenReturn(1L);
        when(lists.findPageIds(any(), anyLong(), anyInt())).thenReturn(List.of());

        service.listCashierShifts(CashierShiftStatus.CLOSED, LocalDate.parse("2026-10-21"), 9L, -5, 1000);

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(lists).findPageIds(filter.capture(), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(100));
        assertThat(filter.getValue()).isEqualTo(new Filter(BRANCH, CashierShiftStatus.CLOSED,
                LocalDate.parse("2026-10-21"), 9L));
    }

    @Test
    void listKeepsTheOrderOfTheIdPage() {
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        CashierShift a = mock(CashierShift.class);
        CashierShift b = mock(CashierShift.class);
        when(a.getId()).thenReturn(5L);
        when(b.getId()).thenReturn(9L);
        when(lists.count(any())).thenReturn(2L);
        when(lists.findPageIds(any(), anyLong(), anyInt())).thenReturn(List.of(9L, 5L));
        when(shifts.findAllById(List.of(9L, 5L))).thenReturn(List.of(a, b));
        CashierShiftResponse r9 = mock(CashierShiftResponse.class);
        CashierShiftResponse r5 = mock(CashierShiftResponse.class);
        when(assembler.toResponses(List.of(b, a))).thenReturn(List.of(r9, r5));

        PageResponse<CashierShiftResponse> page = service.listCashierShifts(null, null, null, 0, 20);

        assertThat(page.content()).containsExactly(r9, r5);
        assertThat(page.totalElements()).isEqualTo(2L);
    }

    @Test
    void receptionistSeesOnlyOwnShift() {
        CashierShift own = shift(ME, BRANCH);
        when(shifts.findById(11L)).thenReturn(Optional.of(own));
        when(principal.role()).thenReturn("RECEPTIONIST");
        CashierShiftResponse response = mock(CashierShiftResponse.class);
        when(assembler.toResponse(own)).thenReturn(response);

        assertThat(service.getCashierShift(11L)).isSameAs(response);
    }

    @Test
    void receptionistCannotSeeAnotherCashiersShift() {
        when(shifts.findById(11L)).thenReturn(Optional.of(shift(ME + 1, BRANCH)));
        when(principal.role()).thenReturn("RECEPTIONIST");

        assertThatThrownBy(() -> service.getCashierShift(11L)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void managerOfAnotherBranchIs403ThroughBranchScope() {
        when(shifts.findById(11L)).thenReturn(Optional.of(shift(ME + 1, BRANCH)));
        when(principal.role()).thenReturn("BRANCH_MANAGER");
        org.mockito.Mockito.doThrow(new AccessDeniedScopeException("branch:3", "branch:4")).when(branchScope)
                .check(BRANCH);

        assertThatThrownBy(() -> service.getCashierShift(11L)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void missingShiftIs404() {
        when(shifts.findById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCashierShift(11L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
