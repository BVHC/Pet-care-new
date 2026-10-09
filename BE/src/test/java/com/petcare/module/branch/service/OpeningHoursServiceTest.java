package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi.BookedStay;
import com.petcare.module.branch.api.BranchClinicCancellationEvent;
import com.petcare.module.branch.api.BranchClinicCancellationEvent.Cause;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.dto.DayHoursDto;
import com.petcare.module.branch.dto.OpeningHoursImpactRequest;
import com.petcare.module.branch.dto.OpeningHoursVersionResponse;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.dto.SetOpeningHoursRequest;
import com.petcare.module.branch.dto.TimeRangeDto;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.OpeningHoursRepository;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.module.branch.service.ScheduleImpactFinder.Impact;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** UC14: BR-CN-02, BR-CN-04 (ngày hiệu lực, từ chối hoặc hủy hàng loạt), BR-LH-10. Hôm nay = thứ Sáu 2026-10-09. */
@ExtendWith(MockitoExtension.class)
class OpeningHoursServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Mock BranchRepository branches;
    @Mock OpeningHoursRepository openingHours;
    @Mock ScheduleLoader scheduleLoader;
    @Mock ScheduleImpactFinder impactFinder;
    @Mock ApplicationEventPublisher events;
    @Mock BranchScope scope;

    OpeningHoursService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new OpeningHoursService(branches, openingHours, scheduleLoader, impactFinder, events, scope, clock);
    }

    private Branch branch(BranchStatus status) {
        Branch branch = new Branch("CN", "Địa chỉ", "0281234567", BigDecimal.ONE, BigDecimal.ONE, false);
        ReflectionTestUtils.setField(branch, "id", 3L);
        ReflectionTestUtils.setField(branch, "status", status);
        return branch;
    }

    private static List<DayHoursDto> week() {
        List<DayHoursDto> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(d == 7 ? new DayHoursDto(d, List.of())
                    : new DayHoursDto(d, List.of(new TimeRangeDto(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                            new TimeRangeDto(LocalTime.of(14, 0), LocalTime.of(19, 0)))));
        }
        return days;
    }

    private static SetOpeningHoursRequest set(LocalDate from, Boolean cancel) {
        return new SetOpeningHoursRequest(from, week(), cancel);
    }

    private void lockedBranch(BranchStatus status) {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch(status)));
        lenient().when(scheduleLoader.load(3L)).thenReturn(new BranchSchedule(List.of(), List.of()));
    }

    private static Impact noImpact() {
        return new Impact(List.of(), List.of());
    }

    private static Impact someImpact() {
        return new Impact(
                List.of(new BookedSlot(11L, "LH-1", 3L, 5L, 6L, LocalDate.of(2026, 11, 2), LocalTime.of(15, 0)),
                        new BookedSlot(12L, "LH-2", 3L, 5L, 6L, LocalDate.of(2026, 11, 3), LocalTime.of(15, 30))),
                List.of(new BookedStay(21L, "LT-1", 5L, 6L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4))));
    }

    private void actor() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(9L);
        when(scope.current()).thenReturn(principal);
    }

    // -------------------------------------------------------------- đặt giờ mở cửa

    @Test
    void draftBranchMayStartTodayAndNeverAsksOtherModulesForBookings() {
        lockedBranch(BranchStatus.DRAFT);

        OpeningHoursVersionResponse response = service.setOpeningHours(3L, set(TODAY, true));

        assertThat(response.effectiveFrom()).isEqualTo(TODAY);
        assertThat(response.days()).hasSize(7);
        verify(openingHours).saveAll(anyList());
        verify(events, never()).publishEvent(any(Object.class));
        verifyNoInteractions(impactFinder);     // DRAFT chưa nhận đặt lịch (BR-CN-01): không thể có lịch BOOKED
    }

    @Test
    void previewOnADraftBranchIsEmptyWithoutCallingOtherModules() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        ScheduleImpact empty = new ScheduleImpact(List.of(), List.of());
        when(impactFinder.describe(Impact.NONE)).thenReturn(empty);

        assertThat(service.previewImpact(3L, new OpeningHoursImpactRequest(TODAY, week()))).isSameAs(empty);

        verify(impactFinder, never()).forHoursChange(any(), any(), any());
    }

    @Test
    void draftBranchCannotStartInThePast_BR_CN_04() {
        lockedBranch(BranchStatus.DRAFT);

        assertThatThrownBy(() -> service.setOpeningHours(3L, set(TODAY.minusDays(1), null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-CN-04"));
        verify(openingHours, never()).saveAll(anyList());
    }

    @Test
    void activeBranchCannotStartTodayButMayStartTomorrow_BR_CN_04() {
        lockedBranch(BranchStatus.ACTIVE);
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(noImpact());

        assertThatThrownBy(() -> service.setOpeningHours(3L, set(TODAY, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-CN-04"));

        assertThat(service.setOpeningHours(3L, set(TOMORROW, null)).effectiveFrom()).isEqualTo(TOMORROW);
    }

    @Test
    void invalidHoursAreRejectedBeforeAnyOtherModuleIsCalled_BR_CN_02() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        List<DayHoursDto> broken = new ArrayList<>(week());
        broken.set(0, new DayHoursDto(1, List.of(new TimeRangeDto(LocalTime.of(19, 0), LocalTime.of(8, 0)))));

        assertThatThrownBy(() -> service.setOpeningHours(3L, new SetOpeningHoursRequest(TOMORROW, broken, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-CN-02"));
        verifyNoInteractions(impactFinder, scheduleLoader);
    }

    @Test
    void affectedBookingsWithoutCancelAreRejectedAndNothingIsWritten_BR_CN_04() {
        lockedBranch(BranchStatus.ACTIVE);
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(someImpact());

        assertThatThrownBy(() -> service.setOpeningHours(3L, set(TOMORROW, false))).satisfies(t -> {
            assertThat(t).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                    e -> assertThat(e.getRuleId()).isEqualTo("BR-CN-04"));
            assertThat(t.getMessage()).contains("2 lịch hẹn").contains("1 đặt chỗ");
        });
        verify(openingHours, never()).deleteByBranchIdAndEffectiveFrom(any(), any());
        verify(openingHours, never()).saveAll(anyList());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void cancelAffectedSavesThenPublishesTheEventWithExactIds() {
        lockedBranch(BranchStatus.ACTIVE);
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(someImpact());
        actor();

        service.setOpeningHours(3L, set(TOMORROW, true));

        InOrder order = inOrder(openingHours, events);
        order.verify(openingHours).deleteByBranchIdAndEffectiveFrom(3L, TOMORROW);
        order.verify(openingHours).flush();
        order.verify(openingHours).saveAll(anyList());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new BranchClinicCancellationEvent(3L, Cause.OPENING_HOURS_REDUCED,
                List.of(11L, 12L), List.of(21L), 9L));
    }

    @Test
    void cancelAffectedWithNothingAffectedPublishesNothing() {
        lockedBranch(BranchStatus.ACTIVE);
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(noImpact());

        service.setOpeningHours(3L, set(TOMORROW, true));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void impactIsComputedOnTheScheduleThatAlreadyContainsTheNewVersion() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        Version older = new Version(LocalDate.of(2026, 1, 1), Map.of(1, List.of(new TimeRange(
                LocalTime.of(8, 0), LocalTime.of(19, 0)))));
        when(scheduleLoader.load(3L)).thenReturn(new BranchSchedule(List.of(older), List.of()));
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(noImpact());

        service.setOpeningHours(3L, set(TOMORROW, null));

        ArgumentCaptor<BranchSchedule> captor = ArgumentCaptor.forClass(BranchSchedule.class);
        verify(impactFinder).forHoursChange(org.mockito.ArgumentMatchers.eq(3L), captor.capture(),
                org.mockito.ArgumentMatchers.eq(TOMORROW));
        // Từ ngày mai bản mới thay bản cũ: thứ Hai 2026-10-12 theo bản mới; thứ Sáu hôm nay (trước hiệu lực) theo
        // bản cũ, vốn chỉ mở thứ Hai nên rỗng.
        assertThat(captor.getValue().rangesOn(LocalDate.of(2026, 10, 12)))
                .containsExactly(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                        new TimeRange(LocalTime.of(14, 0), LocalTime.of(19, 0)));
        assertThat(captor.getValue().rangesOn(LocalDate.of(2026, 10, 9))).isEmpty();
    }

    @Test
    void setOnUnknownBranchIsNotFound() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setOpeningHours(3L, set(TOMORROW, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------------- xem trước

    @Test
    void previewReturnsTheDescribedImpactAndWritesNothing() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        when(scheduleLoader.load(3L)).thenReturn(new BranchSchedule(List.of(), List.of()));
        Impact impact = someImpact();
        when(impactFinder.forHoursChange(any(), any(), any())).thenReturn(impact);
        ScheduleImpact described = new ScheduleImpact(List.of(), List.of());
        when(impactFinder.describe(impact)).thenReturn(described);

        assertThat(service.previewImpact(3L, new OpeningHoursImpactRequest(TOMORROW, week()))).isSameAs(described);

        verifyNoInteractions(events);
        verify(openingHours, never()).saveAll(anyList());
        verify(scope).check(3L);
    }

    // -------------------------------------------------------------- xem

    @Test
    void listShowsTheCurrentVersionAndFutureOnes() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        Version old = new Version(LocalDate.of(2026, 1, 1), Map.of());
        Version current = new Version(LocalDate.of(2026, 9, 1), Map.of());
        Version future = new Version(LocalDate.of(2026, 12, 1), Map.of());
        when(scheduleLoader.loadVersions(3L)).thenReturn(List.of(old, current, future));

        assertThat(service.listVersions(3L)).extracting(OpeningHoursVersionResponse::effectiveFrom)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1));
    }

    @Test
    void listShowsEverythingWhenNothingIsInForceYet() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        Version future = new Version(LocalDate.of(2026, 12, 1), Map.of());
        when(scheduleLoader.loadVersions(3L)).thenReturn(List.of(future));

        assertThat(service.listVersions(3L)).hasSize(1);
        assertThat(service.listVersions(3L).get(0).days()).hasSize(7);
    }

    @Test
    void listForAnotherBranchIsRefusedByTheScope() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        org.mockito.Mockito.doThrow(new com.petcare.platform.exception.AccessDeniedScopeException("branch:3", "branch:4"))
                .when(scope).check(3L);

        assertThatThrownBy(() -> service.listVersions(3L))
                .isInstanceOf(com.petcare.platform.exception.AccessDeniedScopeException.class);
        verifyNoInteractions(scheduleLoader);
    }
}
