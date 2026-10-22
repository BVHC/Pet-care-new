package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.entity.ShiftTotals;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.service.CashierShiftAutoCloseService.Result;

/** ST13 tự chốt ca thu ngân (Ca thu ngân#4, #5; BR-TG-05; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class CashierShiftAutoCloseServiceTest {

    private static final long SHIFT_ID = 40L;
    private static final long BRANCH = 3L;
    private static final long CASHIER = 7L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @Mock
    CashierShiftRepository shifts;
    @Mock
    BranchQueryApi branches;
    @Mock
    StaffDirectoryApi staffDirectory;
    @Mock
    SystemConfigApi configs;
    @Mock
    NotificationApi notifications;
    @Mock
    ShiftNoticeRecipients recipients;
    @Mock
    ShiftTotalsReader totals;

    CashierShiftAutoCloseService service;

    @BeforeEach
    void setUp() {
        service = new CashierShiftAutoCloseService(shifts, branches, staffDirectory, configs, notifications,
                recipients, totals, new CashierShiftTransitionHandler());
        lenient().when(configs.getInt(ConfigKey.CASHIER_SHIFT_AUTO_CLOSE_GRACE_MINUTES)).thenReturn(30);
        lenient().when(branches.openingRangesOn(BRANCH, DAY)).thenReturn(List.of(
                new TimeRange(LocalTime.of(8, 0), LocalTime.of(18, 0))));
        lenient().when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh Q1", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, true)));
        lenient().when(staffDirectory.findStaff(CASHIER)).thenReturn(Optional.of(
                new StaffSummary(CASHIER, "Trần Thị Bình", Role.RECEPTIONIST, BRANCH, true, false)));
        lenient().when(recipients.forBranch(BRANCH)).thenReturn(List.of(11L, 12L));
        lenient().when(totals.read(SHIFT_ID)).thenReturn(new ShiftTotals(150_000, 30_000));
    }

    private CashierShift openShift(boolean afterHours, Instant openedAt) {
        CashierShift shift = CashierShift.open(BRANCH, CASHIER, afterHours, DAY, openedAt);
        ReflectionTestUtils.setField(shift, "id", SHIFT_ID);
        when(shifts.lockOpenSkipLocked(SHIFT_ID)).thenReturn(Optional.of(shift));
        return shift;
    }

    private static Instant at(String vietnamTime) {
        return LocalDateTime.parse(vietnamTime).atZone(ZONE).toInstant();
    }

    @Test
    void notDueLeavesTheShiftOpen() {
        CashierShift shift = openShift(false, at("2026-10-21T08:05:00"));

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T18:29:59"))).isEqualTo(Result.NOT_DUE);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.OPEN);
        verify(shifts, never()).saveAndFlush(any());
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void dueAtTheBoundaryClosesAndNotifiesEveryManager() {
        CashierShift shift = openShift(false, at("2026-10-21T08:05:00"));

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T18:30:00"))).isEqualTo(Result.CLOSED);

        InOrder order = inOrder(shifts, totals, notifications);
        order.verify(shifts).lockOpenSkipLocked(SHIFT_ID);
        order.verify(totals).read(SHIFT_ID);
        order.verify(shifts).saveAndFlush(shift);
        order.verify(notifications, times(2)).enqueue(any(NotificationRequest.class));
        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
        assertThat(shift.isAutoClosed()).isTrue();
        assertThat(shift.getCountedCash()).isNull();
        assertThat(shift.getExpectedCash()).isEqualTo(150_000L);
        assertThat(shift.getExpectedTransfer()).isEqualTo(30_000L);
    }

    @Test
    void noticePayloadCarriesEveryRequiredVariableOfTheTemplate() {
        openShift(false, at("2026-10-21T08:05:00"));

        service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T19:00:00"));

        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(request.capture());
        NotificationRequest first = request.getAllValues().get(0);
        assertThat(first.templateCode()).isEqualTo("CASHIER_SHIFT_AUTO_CLOSED_APP");
        assertThat(first.channel()).isEqualTo(Channel.IN_APP);
        assertThat(first.recipientAccountId()).isEqualTo(11L);
        assertThat(first.recipientEmail()).isNull();
        assertThat(first.payload().keySet()).isEqualTo(Set.of("ma_ca", "loai_ca", "ten_thu_ngan", "chi_nhanh",
                "ngay_ca", "gio_chot"));
        assertThat(first.payload()).containsEntry("ma_ca", String.valueOf(SHIFT_ID))
                .containsEntry("loai_ca", "thường").containsEntry("ten_thu_ngan", "Trần Thị Bình")
                .containsEntry("chi_nhanh", "Chi nhánh Q1").containsEntry("ngay_ca", "21/10/2026")
                .containsEntry("gio_chot", "19:00 21/10/2026");
        assertThat(request.getAllValues().get(1).recipientAccountId()).isEqualTo(12L);
    }

    @Test
    void afterHoursShiftUsesTheNextOpeningStartCountedFromWhenItWasOpened() {
        Instant opened = at("2026-10-21T22:00:00");
        CashierShift shift = openShift(true, opened);
        when(branches.nextOpeningStart(BRANCH, LocalDateTime.of(2026, 10, 21, 22, 0)))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 10, 22, 8, 0)));

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-22T07:59:59"))).isEqualTo(Result.NOT_DUE);
        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-22T08:00:00"))).isEqualTo(Result.CLOSED);

        assertThat(shift.isAutoClosed()).isTrue();
        verify(branches, never()).openingRangesOn(eq(BRANCH), any());
    }

    @Test
    void afterHoursShiftWithoutANextOpeningHasNoDeadline() {
        CashierShift shift = openShift(true, at("2026-10-21T22:00:00"));
        when(branches.nextOpeningStart(any(), any())).thenReturn(Optional.empty());

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-12-31T08:00:00")))
                .isEqualTo(Result.NO_DEADLINE);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.OPEN);
        verify(shifts, never()).saveAndFlush(any());
    }

    @Test
    void holidayDeclaredAfterOpeningMeansMidnightOfTheNextDay() {
        CashierShift shift = openShift(false, at("2026-10-21T08:05:00"));
        when(branches.openingRangesOn(BRANCH, DAY)).thenReturn(List.of());

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T23:59:59"))).isEqualTo(Result.NOT_DUE);
        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-22T00:00:00"))).isEqualTo(Result.CLOSED);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
    }

    @Test
    void shiftThatIsLockedOrNoLongerOpenIsSkipped() {
        when(shifts.lockOpenSkipLocked(SHIFT_ID)).thenReturn(Optional.empty());

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-22T09:00:00"))).isEqualTo(Result.SKIPPED);

        verify(shifts, never()).saveAndFlush(any());
        verify(totals, never()).read(any());
    }

    @Test
    void graceComesFromTheConfigurationAtRunTime() {
        openShift(false, at("2026-10-21T08:05:00"));
        when(configs.getInt(ConfigKey.CASHIER_SHIFT_AUTO_CLOSE_GRACE_MINUTES)).thenReturn(60);

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T18:59:59"))).isEqualTo(Result.NOT_DUE);
        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T19:00:00"))).isEqualTo(Result.CLOSED);
    }

    @Test
    void shiftIsStillClosedWhenNobodyCanBeNotified() {
        CashierShift shift = openShift(false, at("2026-10-21T08:05:00"));
        when(recipients.forBranch(BRANCH)).thenReturn(List.of());

        assertThat(service.autoCloseCashierShift(SHIFT_ID, at("2026-10-21T19:00:00"))).isEqualTo(Result.CLOSED);

        assertThat(shift.getStatus()).isEqualTo(CashierShiftStatus.CLOSED);
        verify(notifications, never()).enqueue(any());
    }
}
