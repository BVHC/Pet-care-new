package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import java.util.List;
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
import com.petcare.module.branch.dto.CreateHolidayRequest;
import com.petcare.module.branch.dto.HolidayImpactRequest;
import com.petcare.module.branch.dto.HolidayResponse;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.entity.Holiday;
import com.petcare.module.branch.mapper.BranchMapperImpl;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.HolidayRepository;
import com.petcare.module.branch.service.ScheduleImpactFinder.Impact;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/** UC14: BR-CN-03 (ngày nghỉ), BR-LH-10 (từ chối hoặc hủy hàng loạt), BR-LT-04. Hôm nay = 2026-10-09. */
@ExtendWith(MockitoExtension.class)
class HolidayServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Mock BranchRepository branches;
    @Mock HolidayRepository holidays;
    @Mock ScheduleImpactFinder impactFinder;
    @Mock ApplicationEventPublisher events;
    @Mock BranchScope scope;

    HolidayService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new HolidayService(branches, holidays, impactFinder, events, scope, new BranchMapperImpl(), clock);
    }

    private Branch branch() {
        return branch(BranchStatus.ACTIVE);
    }

    private Branch branch(BranchStatus status) {
        Branch branch = new Branch("CN", "Địa chỉ", "0281234567", BigDecimal.ONE, BigDecimal.ONE, false);
        ReflectionTestUtils.setField(branch, "id", 3L);
        ReflectionTestUtils.setField(branch, "status", status);
        return branch;
    }

    private void lockedBranch() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch()));
    }

    private void actor() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(9L);
        when(scope.current()).thenReturn(principal);
    }

    private static Impact impact() {
        return new Impact(
                List.of(new BookedSlot(11L, "LH-1", 3L, 5L, 6L, DAY, LocalTime.of(9, 0))),
                List.of(new BookedStay(21L, "LT-1", 5L, 6L, DAY.minusDays(2), DAY),
                        new BookedStay(22L, "LT-2", 5L, 6L, DAY, DAY.plusDays(3))));
    }

    private static Impact none() {
        return new Impact(List.of(), List.of());
    }

    private Holiday holiday(long id, LocalDate date) {
        Holiday holiday = new Holiday(3L, date, "Lễ", 9L);
        ReflectionTestUtils.setField(holiday, "id", id);
        return holiday;
    }

    private static void assertRule(Throwable thrown, String ruleId) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo(ruleId));
    }

    // -------------------------------------------------------------- thêm

    @Test
    void addsAHolidayWithNothingBookedAndRecordsTheActor() {
        lockedBranch();
        actor();
        when(impactFinder.forHoliday(3L, DAY)).thenReturn(none());
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        HolidayResponse response = service.addHoliday(3L, new CreateHolidayRequest(DAY, "Giỗ Tổ", null));

        assertThat(response.holidayDate()).isEqualTo(DAY);
        assertThat(response.reason()).isEqualTo("Giỗ Tổ");
        ArgumentCaptor<Holiday> captor = ArgumentCaptor.forClass(Holiday.class);
        verify(holidays).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(9L);
        assertThat(captor.getValue().getBranchId()).isEqualTo(3L);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void todayIsAllowedForAnIncident() {
        lockedBranch();
        actor();
        when(impactFinder.forHoliday(3L, TODAY)).thenReturn(none());
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.addHoliday(3L, new CreateHolidayRequest(TODAY, null, null)).holidayDate())
                .isEqualTo(TODAY);
    }

    @Test
    void pastDateIsRejected_BR_CN_03() {
        lockedBranch();

        assertThatThrownBy(() -> service.addHoliday(3L, new CreateHolidayRequest(TODAY.minusDays(1), null, null)))
                .satisfies(t -> assertRule(t, "BR-CN-03"));
        verifyNoInteractions(impactFinder);
        verify(holidays, never()).saveAndFlush(any());
    }

    @Test
    void duplicateDateIsRejected_BR_CN_03() {
        lockedBranch();
        when(holidays.existsByBranchIdAndHolidayDate(3L, DAY)).thenReturn(true);

        assertThatThrownBy(() -> service.addHoliday(3L, new CreateHolidayRequest(DAY, null, null)))
                .satisfies(t -> assertRule(t, "BR-CN-03"));
        verifyNoInteractions(impactFinder);
    }

    @Test
    void bookedItemsWithoutCancelAreRejectedAndNothingIsWritten_BR_LH_10() {
        lockedBranch();
        when(impactFinder.forHoliday(3L, DAY)).thenReturn(impact());

        assertThatThrownBy(() -> service.addHoliday(3L, new CreateHolidayRequest(DAY, null, false)))
                .satisfies(t -> {
                    assertRule(t, "BR-LH-10");
                    assertThat(t.getMessage()).contains("1 lịch hẹn").contains("2 đặt chỗ");
                });
        verify(holidays, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void cancelAffectedSavesThenPublishesTheEventWithExactIds() {
        lockedBranch();
        actor();
        when(impactFinder.forHoliday(3L, DAY)).thenReturn(impact());
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addHoliday(3L, new CreateHolidayRequest(DAY, null, true));

        InOrder order = inOrder(holidays, events);
        order.verify(holidays).saveAndFlush(any(Holiday.class));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new BranchClinicCancellationEvent(3L, Cause.HOLIDAY_ADDED,
                List.of(11L), List.of(21L, 22L), 9L));
    }

    @Test
    void cancelAffectedWithNothingAffectedPublishesNothing() {
        lockedBranch();
        actor();
        when(impactFinder.forHoliday(3L, DAY)).thenReturn(none());
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addHoliday(3L, new CreateHolidayRequest(DAY, null, true));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void draftBranchSkipsTheBookingCheckEvenWhenAskedToCancel() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        actor();
        when(holidays.saveAndFlush(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addHoliday(3L, new CreateHolidayRequest(DAY, null, true));

        verifyNoInteractions(impactFinder, events);     // DRAFT chưa nhận đặt lịch (BR-CN-01)
    }

    @Test
    void previewOnADraftBranchIsEmptyWithoutCallingOtherModules() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        ScheduleImpact empty = new ScheduleImpact(List.of(), List.of());
        when(impactFinder.describe(Impact.NONE)).thenReturn(empty);

        assertThat(service.previewImpact(3L, new HolidayImpactRequest(DAY))).isSameAs(empty);

        verify(impactFinder, never()).forHoliday(any(), any());
    }

    @Test
    void addOnUnknownBranchIsNotFound() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addHoliday(3L, new CreateHolidayRequest(DAY, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------------- xem trước, xem

    @Test
    void previewReturnsTheDescribedImpactAndWritesNothing() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        Impact impact = impact();
        when(impactFinder.forHoliday(3L, DAY)).thenReturn(impact);
        ScheduleImpact described = new ScheduleImpact(List.of(), List.of());
        when(impactFinder.describe(impact)).thenReturn(described);

        assertThat(service.previewImpact(3L, new HolidayImpactRequest(DAY))).isSameAs(described);

        verify(scope).check(3L);
        verify(holidays, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void listFiltersByTheInclusiveRange() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(holidays.findByBranchIdOrderByHolidayDateAsc(3L)).thenReturn(List.of(
                holiday(1, LocalDate.of(2026, 10, 10)), holiday(2, LocalDate.of(2026, 10, 20)),
                holiday(3, LocalDate.of(2026, 10, 30)), holiday(4, LocalDate.of(2026, 11, 20))));

        assertThat(service.listHolidays(3L, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 30)))
                .extracting(HolidayResponse::holidayId).containsExactly(2L, 3L);
        assertThat(service.listHolidays(3L, null, null)).hasSize(4);
        assertThat(service.listHolidays(3L, LocalDate.of(2026, 11, 1), null))
                .extracting(HolidayResponse::holidayId).containsExactly(4L);
        assertThat(service.listHolidays(3L, null, LocalDate.of(2026, 10, 10)))
                .extracting(HolidayResponse::holidayId).containsExactly(1L);
    }

    // -------------------------------------------------------------- xóa

    @Test
    void deletesAFutureHoliday() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        Holiday holiday = holiday(5, TODAY.plusDays(1));
        when(holidays.findByIdAndBranchId(5L, 3L)).thenReturn(Optional.of(holiday));

        service.deleteHoliday(3L, 5L);

        verify(holidays).delete(holiday);
    }

    @Test
    void cannotDeleteTodayOrAPastHoliday_BR_CN_03() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(holidays.findByIdAndBranchId(5L, 3L)).thenReturn(Optional.of(holiday(5, TODAY)));
        when(holidays.findByIdAndBranchId(6L, 3L)).thenReturn(Optional.of(holiday(6, TODAY.minusDays(3))));

        assertThatThrownBy(() -> service.deleteHoliday(3L, 5L)).satisfies(t -> assertRule(t, "BR-CN-03"));
        assertThatThrownBy(() -> service.deleteHoliday(3L, 6L)).satisfies(t -> assertRule(t, "BR-CN-03"));
        verify(holidays, never()).delete(any(Holiday.class));
    }

    @Test
    void holidayOfAnotherBranchIsNotFound() {
        when(branches.findById(3L)).thenReturn(Optional.of(branch()));
        when(holidays.findByIdAndBranchId(5L, 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteHoliday(3L, 5L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
