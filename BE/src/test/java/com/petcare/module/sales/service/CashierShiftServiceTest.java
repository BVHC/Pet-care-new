package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.OpenShiftRequest;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.OpenShiftRow;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** Mở ca thu ngân (sales-v1 #16; Ca thu ngân#1, #2; BR-TG-01, 05, BR-CN-05; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class CashierShiftServiceTest {

    private static final long ME = 7L;
    private static final long BRANCH = 3L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** 2026-10-21 10:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");

    @Mock
    CashierShiftRepository shifts;
    @Mock
    StaffDirectoryApi staffDirectory;
    @Mock
    BranchQueryApi branches;
    @Mock
    CashierShiftAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;
    @Mock
    OpenShiftRow existing;

    CashierShiftService service;
    CashierShiftResponse response;

    @BeforeEach
    void setUp() {
        service = new CashierShiftService(shifts, staffDirectory, branches, new CashierShiftTransitionHandler(),
                assembler, branchScope, Clock.fixed(NOW, ZONE));
        org.mockito.Mockito.lenient().when(branchScope.current()).thenReturn(principal);
        org.mockito.Mockito.lenient().when(principal.accountId()).thenReturn(ME);
        org.mockito.Mockito.lenient().when(staffDirectory.findStaffForShare(ME)).thenReturn(Optional.of(
                new StaffSummary(ME, "An", Role.RECEPTIONIST, BRANCH, true, false)));
        org.mockito.Mockito.lenient().when(shifts.findOpenShiftRow(ME)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "CN", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, true)));
        org.mockito.Mockito.lenient().when(shifts.saveAndFlush(any(CashierShift.class)))
                .thenAnswer(call -> call.getArgument(0));
        response = org.mockito.Mockito.mock(CashierShiftResponse.class);
        org.mockito.Mockito.lenient().when(assembler.toResponse(any(CashierShift.class))).thenReturn(response);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    private CashierShift savedShift() {
        ArgumentCaptor<CashierShift> captor = ArgumentCaptor.forClass(CashierShift.class);
        verify(shifts).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void opensRegularShiftInsideOpeningHours() {
        when(branches.isOpenAt(BRANCH, local(NOW))).thenReturn(true);

        assertThat(service.openCashierShift(new OpenShiftRequest(false))).isSameAs(response);

        CashierShift saved = savedShift();
        assertThat(saved.isAfterHours()).isFalse();
        assertThat(saved.getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getCashierId()).isEqualTo(ME);
        assertThat(saved.getBusinessDate()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(saved.getOpenedAt()).isEqualTo(NOW);
    }

    @Test
    void locksOwnAccountBeforeReadingTheOpenShift() {
        when(branches.isOpenAt(BRANCH, local(NOW))).thenReturn(true);

        service.openCashierShift(new OpenShiftRequest(false));

        InOrder order = inOrder(staffDirectory, shifts);
        order.verify(staffDirectory).findStaffForShare(ME);
        order.verify(shifts).findOpenShiftRow(ME);
        order.verify(shifts).saveAndFlush(any(CashierShift.class));
    }

    @Test
    void secondOpenShiftIs409BeforeAnyOpeningHoursRule() {
        when(shifts.findOpenShiftRow(ME)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(true)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(branches);
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void regularShiftOutsideOpeningHoursIsBrTg05() {
        when(branches.isOpenAt(BRANCH, local(NOW))).thenReturn(false);

        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(false)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-05");
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void afterHoursShiftOnBranchWithoutFlagIsBrCn05() {
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "CN", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false)));

        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(true)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-CN-05");
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void afterHoursShiftInsideOpeningHoursIsBrTg05() {
        when(branches.isOpenAt(BRANCH, local(NOW))).thenReturn(true);

        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(true)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-05");
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void afterHoursShiftOutsideOpeningHoursOpensWithTheFlag() {
        when(branches.isOpenAt(BRANCH, local(NOW))).thenReturn(false);

        service.openCashierShift(new OpenShiftRequest(true));

        assertThat(savedShift().isAfterHours()).isTrue();
    }

    @Test
    void afterHoursShiftAfterMidnightBelongsToTheOpeningDate() {
        Instant afterMidnight = Instant.parse("2026-10-21T17:30:00Z"); // 00:30 ngày 22/10 giờ Việt Nam
        service = new CashierShiftService(shifts, staffDirectory, branches, new CashierShiftTransitionHandler(),
                assembler, branchScope, Clock.fixed(afterMidnight, ZONE));
        when(branches.isOpenAt(BRANCH, local(afterMidnight))).thenReturn(false);

        service.openCashierShift(new OpenShiftRequest(true));

        assertThat(savedShift().getBusinessDate()).isEqualTo(LocalDate.parse("2026-10-22"));
    }

    @Test
    void staffThatIsNotAnActiveReceptionistWithABranchIsAProgrammingError() {
        when(staffDirectory.findStaffForShare(ME)).thenReturn(Optional.of(
                new StaffSummary(ME, "An", Role.VET, BRANCH, true, false)));

        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(false)))
                .isInstanceOf(IllegalStateException.class);

        when(staffDirectory.findStaffForShare(ME)).thenReturn(Optional.of(
                new StaffSummary(ME, "An", Role.RECEPTIONIST, BRANCH, false, false)));
        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(false)))
                .isInstanceOf(IllegalStateException.class);

        when(staffDirectory.findStaffForShare(ME)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.openCashierShift(new OpenShiftRequest(false)))
                .isInstanceOf(IllegalStateException.class);
        verify(shifts, never()).saveAndFlush(any());
    }
}
