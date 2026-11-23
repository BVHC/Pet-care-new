package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftRegistrationMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftRegistrationService} #17 (docs/adr/0149, mục "Đăng ký tuần — đọc #17"): thứ tự guard → truy vấn → ghép →
 * định mức; guard 400 {@code BR-NS-11} (không phải thứ Hai, năm ngoài 2000–2100) không chạm repository; {@code staffId}
 * chỉ từ principal; {@code editableUntil} là 00:00 thứ Hai giờ Việt Nam; lỗi từ repository / ghép lan ra nguyên vẹn.
 */
class ShiftRegistrationServiceTest {

    private static final long STAFF = 12L;
    /** 2026-11-23 là thứ Hai. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftAssignmentService shiftAssignments = mock(ShiftAssignmentService.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final ShiftRegistrationMapper mapper = Mappers.getMapper(ShiftRegistrationMapper.class);
    private final ShiftRegistrationService service = new ShiftRegistrationService(branchScope,
            mock(StaffDirectoryApi.class), assignments, mock(ShiftDefinitionRepository.class),
            mock(ShiftRequestRepository.class), shiftAssignments, new ShiftAssignmentTransitionHandler(), mapper,
            configs, Clock.systemUTC());

    {
        when(branchScope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(STAFF);
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(10);
    }

    @Test
    void readsPrincipalThenValidatesThenQueriesTheWeekThenAssemblesThenReadsTheQuota() {
        List<ShiftAssignment> rows = List.of(ShiftAssignment.schedule(5L, STAFF, 7L, MONDAY, ShiftAssignmentSource.SELF));
        List<ShiftAssignmentResponse> list = List.of(response(1L, ShiftAssignmentSource.SELF));
        when(assignments.findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6))).thenReturn(rows);
        when(shiftAssignments.toResponses(rows)).thenReturn(list);

        ShiftRegistrationResponse result = service.getMyShiftRegistration(MONDAY);

        InOrder order = inOrder(branchScope, assignments, shiftAssignments, configs);
        order.verify(branchScope).current();
        order.verify(assignments).findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6));
        order.verify(shiftAssignments).toResponses(rows);
        order.verify(configs).getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
        assertThat(result).isEqualTo(new ShiftRegistrationResponse(MONDAY, 10, 1, Instant.parse("2026-11-22T17:00:00Z"),
                list));
    }

    @Test
    void theStaffIdComesOnlyFromThePrincipalAndTheWindowIsMondayToSundayInclusive() {
        when(assignments.findScheduledInWeek(STAFF, MONDAY, LocalDate.of(2026, 11, 29))).thenReturn(List.of());
        when(shiftAssignments.toResponses(List.of())).thenReturn(List.of());

        service.getMyShiftRegistration(MONDAY);

        verify(assignments).findScheduledInWeek(STAFF, MONDAY, LocalDate.of(2026, 11, 29));
    }

    @Test
    void anEmptyWeekIsZeroShiftsAndAnEmptyListWithTheQuotaStillReported() {
        when(assignments.findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6))).thenReturn(List.of());
        when(shiftAssignments.toResponses(List.of())).thenReturn(List.of());

        ShiftRegistrationResponse result = service.getMyShiftRegistration(MONDAY);

        assertThat(result.shiftCount()).isZero();
        assertThat(result.assignments()).isNotNull().isEmpty();
        assertThat(result.requiredShifts()).isEqualTo(10);
    }

    @Test
    void theQuotaIsTheCurrentConfigValue() {
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(13);
        when(assignments.findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6))).thenReturn(List.of());
        when(shiftAssignments.toResponses(List.of())).thenReturn(List.of());

        assertThat(service.getMyShiftRegistration(MONDAY).requiredShifts()).isEqualTo(13);
    }

    @Test
    void everyDayExceptMondayIsBrNs11AndNothingIsQueried() {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day == DayOfWeek.MONDAY) {
                continue;
            }
            LocalDate date = MONDAY.with(day);
            assertThatThrownBy(() -> service.getMyShiftRegistration(date))
                    .as(day.toString())
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                        assertThat(ex.getRuleId()).isEqualTo("BR-NS-11");
                        assertThat(ex.getMessage()).isEqualTo("Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)");
                    });
        }

        verifyNoInteractions(assignments, shiftAssignments, configs);
    }

    @Test
    void theYearWindowIs2000To2100AndTheMondayCheckComesFirst() {
        LocalDate firstMonday2000 = firstMondayOf(2000);
        LocalDate lastMonday2100 = lastMondayOf(2100);
        when(assignments.findScheduledInWeek(any(), any(), any())).thenReturn(List.of());
        when(shiftAssignments.toResponses(List.of())).thenReturn(List.of());

        assertThat(service.getMyShiftRegistration(firstMonday2000).weekStart()).isEqualTo(firstMonday2000);
        assertThat(service.getMyShiftRegistration(lastMonday2100).weekStart()).isEqualTo(lastMonday2100);

        for (LocalDate outside : List.of(lastMondayOf(1999), firstMondayOf(2101), LocalDate.of(+999_999_999, 12, 27),
                LocalDate.of(-1, 1, 3))) {
            LocalDate monday = outside.getDayOfWeek() == DayOfWeek.MONDAY ? outside
                    : outside.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (monday.getYear() >= 2000 && monday.getYear() <= 2100) {
                continue; // đã kéo về thứ Hai trong khoảng hợp lệ: bỏ qua
            }
            assertThatThrownBy(() -> service.getMyShiftRegistration(monday))
                    .as(monday.toString())
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                        assertThat(ex.getRuleId()).isEqualTo("BR-NS-11");
                        assertThat(ex.getMessage()).isEqualTo("Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100 (BR-NS-11)");
                    });
        }
        // một ngày không phải thứ Hai ở năm ngoài khoảng vẫn báo "thứ Hai" trước
        assertThatThrownBy(() -> service.getMyShiftRegistration(LocalDate.of(1999, 12, 29)))
                .hasMessage("Ngày bắt đầu tuần phải là thứ Hai (BR-NS-11)");
    }

    @Test
    void editableUntilIsMidnightOfMondayInVietnamAndDoesNotDependOnAClock() {
        assertThat(ShiftRegistrationService.editableUntil(MONDAY)).isEqualTo(Instant.parse("2026-11-22T17:00:00Z"));
        assertThat(ShiftRegistrationService.editableUntil(LocalDate.of(2027, 1, 4)))
                .isEqualTo(Instant.parse("2027-01-03T17:00:00Z"));
    }

    @Test
    void aRepositoryFailureIsNotSwallowedAndNothingElseRuns() {
        IllegalStateException failure = new IllegalStateException("DB");
        when(assignments.findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6))).thenThrow(failure);

        assertThatThrownBy(() -> service.getMyShiftRegistration(MONDAY)).isSameAs(failure);

        verifyNoInteractions(shiftAssignments, configs);
    }

    @Test
    void anAssemblyFailureIsNotSwallowedAndTheQuotaIsNotRead() {
        List<ShiftAssignment> rows = List.of(ShiftAssignment.schedule(5L, STAFF, 7L, MONDAY, ShiftAssignmentSource.SELF));
        IllegalStateException failure = new IllegalStateException("Phân ca thiếu nhân viên hoặc ca làm việc");
        when(assignments.findScheduledInWeek(STAFF, MONDAY, MONDAY.plusDays(6))).thenReturn(rows);
        when(shiftAssignments.toResponses(rows)).thenThrow(failure);

        assertThatThrownBy(() -> service.getMyShiftRegistration(MONDAY)).isSameAs(failure);

        verifyNoInteractions(configs);
    }

    private static LocalDate firstMondayOf(int year) {
        return LocalDate.of(year, 1, 1).with(java.time.temporal.TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
    }

    private static LocalDate lastMondayOf(int year) {
        return LocalDate.of(year, 12, 31).with(java.time.temporal.TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY));
    }

    private static ShiftAssignmentResponse response(long id, ShiftAssignmentSource source) {
        return new ShiftAssignmentResponse(id, 5L, STAFF, "An", 7L, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0),
                MONDAY, ShiftAssignmentStatus.SCHEDULED, source, null, null);
    }
}
