package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.ShiftWeeklySummaryResponse;
import com.petcare.module.workforce.mapper.ShiftWeeklySummaryMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.StaffShiftCount;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftWeeklySummaryService} (workforce-v1 #16, docs/adr/0138, mục "Thống kê tuần"): thứ tự
 * {@code requireBranch} → {@code requireWeekStart} → nhân viên → số đếm → định mức; guard 403 / 400 không chạm
 * repository hay identity; chi nhánh chỉ từ principal; không gọi câu đếm khi chi nhánh không có nhân viên; nhân viên
 * không có hàng đếm là 0; sắp tên tiếng Việt rồi id; lỗi từ nguồn lan ra nguyên vẹn.
 */
class ShiftWeeklySummaryServiceTest {

    private static final long BRANCH = 5L;
    /** 2026-11-23 là thứ Hai. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final ShiftWeeklySummaryService service = new ShiftWeeklySummaryService(branchScope, staffDirectory,
            assignments, Mappers.getMapper(ShiftWeeklySummaryMapper.class), configs);

    {
        when(branchScope.current()).thenReturn(principal);
        when(principal.branchId()).thenReturn(BRANCH);
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(10);
        for (Role role : ShiftAssignmentService.SCHEDULABLE_ROLES) {
            when(staffDirectory.findAssignableStaff(BRANCH, role)).thenReturn(List.of());
        }
    }

    private static StaffSummary staff(long id, String name, Role role) {
        return new StaffSummary(id, name, role, BRANCH, true, false);
    }

    /** Dòng đếm dựng tay (không dùng mock lồng trong {@code thenReturn}: Mockito báo UnfinishedStubbing). */
    private static StaffShiftCount count(long staffId, long shifts) {
        return new StaffShiftCount() {
            @Override
            public Long getStaffId() {
                return staffId;
            }

            @Override
            public Long getShiftCount() {
                return shifts;
            }
        };
    }

    private void staffOf(Role role, StaffSummary... members) {
        when(staffDirectory.findAssignableStaff(BRANCH, role)).thenReturn(List.of(members));
    }

    @Test
    void readsPrincipalThenValidatesThenListsStaffThenCountsThenReadsTheQuota() {
        staffOf(Role.VET, staff(31L, "An", Role.VET));
        when(assignments.countScheduledInWeek(anyCollection(), eq(MONDAY), eq(MONDAY.plusDays(6))))
                .thenReturn(List.of(count(31L, 7)));

        List<ShiftWeeklySummaryResponse> result = service.listWeeklySummaries(MONDAY);

        InOrder order = inOrder(branchScope, staffDirectory, assignments, configs);
        order.verify(branchScope).current();
        order.verify(staffDirectory, times(3)).findAssignableStaff(eq(BRANCH), any(Role.class));
        order.verify(assignments).countScheduledInWeek(anyCollection(), eq(MONDAY), eq(MONDAY.plusDays(6)));
        order.verify(configs).getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
        assertThat(result).containsExactly(new ShiftWeeklySummaryResponse(31L, "An", Role.VET, 7, 10, false));
    }

    @Test
    void theBranchComesOnlyFromThePrincipalAndAllThreeSchedulableRolesAreAsked() {
        service.listWeeklySummaries(MONDAY);

        verify(staffDirectory).findAssignableStaff(BRANCH, Role.RECEPTIONIST);
        verify(staffDirectory).findAssignableStaff(BRANCH, Role.VET);
        verify(staffDirectory).findAssignableStaff(BRANCH, Role.CARETAKER);
        verify(staffDirectory, never()).findAssignableStaff(eq(BRANCH), eq(Role.BRANCH_MANAGER));
    }

    @Test
    void aCallerWithoutABranchIs403AndNothingIsQueried() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.listWeeklySummaries(MONDAY)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(staffDirectory, assignments, configs);
    }

    @Test
    void aWeekStartThatIsNotAMondayIs400BrNs11AndNothingIsQueried() {
        assertThatThrownBy(() -> service.listWeeklySummaries(MONDAY.plusDays(1)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-11");

        verifyNoInteractions(staffDirectory, assignments, configs);
    }

    @Test
    void aYearOutsideTheSupportedRangeIs400BeforeAnyDateArithmeticOrQuery() {
        for (LocalDate monday : List.of(LocalDate.of(1999, 12, 27), LocalDate.of(2101, 1, 4),
                LocalDate.of(999_999, 1, 1).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)))) {
            assertThatThrownBy(() -> service.listWeeklySummaries(monday))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-11");
        }

        verifyNoInteractions(staffDirectory, assignments, configs);
    }

    @Test
    void theBranchCheckRunsBeforeTheWeekCheck() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.listWeeklySummaries(MONDAY.plusDays(1)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void aBranchWithoutStaffIsAnEmptyListAndTheCountQueryAndTheQuotaAreNotTouched() {
        List<ShiftWeeklySummaryResponse> result = service.listWeeklySummaries(MONDAY);

        assertThat(result).isNotNull().isEmpty();
        verify(assignments, never()).countScheduledInWeek(any(), any(), any());
        verify(configs, never()).getInt(any());
    }

    @Test
    void theCountIsOneQueryForEveryStaffMemberOfAllThreeRoles() {
        staffOf(Role.RECEPTIONIST, staff(11L, "Lan", Role.RECEPTIONIST));
        staffOf(Role.VET, staff(21L, "Minh", Role.VET), staff(22L, "Nam", Role.VET));
        staffOf(Role.CARETAKER, staff(31L, "Oanh", Role.CARETAKER));
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenReturn(List.of());

        service.listWeeklySummaries(MONDAY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(assignments, times(1)).countScheduledInWeek(ids.capture(), eq(MONDAY), eq(MONDAY.plusDays(6)));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(11L, 21L, 22L, 31L);
    }

    @Test
    void aStaffMemberWithoutACountRowHasZeroShiftsAndDoesNotMeetTheQuota() {
        staffOf(Role.VET, staff(21L, "Minh", Role.VET), staff(22L, "Nam", Role.VET));
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenReturn(List.of(count(22L, 10)));

        List<ShiftWeeklySummaryResponse> result = service.listWeeklySummaries(MONDAY);

        assertThat(result).containsExactly(
                new ShiftWeeklySummaryResponse(21L, "Minh", Role.VET, 0, 10, false),
                new ShiftWeeklySummaryResponse(22L, "Nam", Role.VET, 10, 10, true));
    }

    @Test
    void theQuotaIsReadOnceAndIsTheCurrentConfigValueForEveryRow() {
        staffOf(Role.VET, staff(21L, "Minh", Role.VET), staff(22L, "Nam", Role.VET));
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(13);
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenReturn(List.of(count(21L, 12)));

        List<ShiftWeeklySummaryResponse> result = service.listWeeklySummaries(MONDAY);

        assertThat(result).extracting(ShiftWeeklySummaryResponse::requiredShifts).containsExactly(13, 13);
        assertThat(result).extracting(ShiftWeeklySummaryResponse::meetsRequirement).containsExactly(false, false);
        verify(configs, times(1)).getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
    }

    @Test
    void rowsAreSortedByVietnameseNameAcrossRolesAndTiesByStaffId() {
        staffOf(Role.RECEPTIONIST, staff(1L, "Đào", Role.RECEPTIONIST), staff(2L, "Bình", Role.RECEPTIONIST));
        staffOf(Role.VET, staff(9L, "An", Role.VET), staff(8L, "An", Role.VET), staff(3L, "Em", Role.VET));
        staffOf(Role.CARETAKER, staff(4L, "Dũng", Role.CARETAKER));
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenReturn(List.of());

        List<ShiftWeeklySummaryResponse> result = service.listWeeklySummaries(MONDAY);

        assertThat(result).extracting(ShiftWeeklySummaryResponse::staffName)
                .containsExactly("An", "An", "Bình", "Dũng", "Đào", "Em");
        assertThat(result).extracting(ShiftWeeklySummaryResponse::staffId).containsExactly(8L, 9L, 2L, 4L, 1L, 3L);
    }

    @Test
    void theIdentityOnlineOrderDoesNotLeakIntoTheResponseOrder() {
        List<StaffSummary> onlineFirst = new ArrayList<>();
        onlineFirst.add(new StaffSummary(7L, "Zoe", Role.VET, BRANCH, true, true));
        onlineFirst.add(new StaffSummary(6L, "Ann", Role.VET, BRANCH, true, false));
        when(staffDirectory.findAssignableStaff(BRANCH, Role.VET)).thenReturn(onlineFirst);
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenReturn(List.of());

        assertThat(service.listWeeklySummaries(MONDAY)).extracting(ShiftWeeklySummaryResponse::staffName)
                .containsExactly("Ann", "Zoe");
    }

    @Test
    void aFailureFromTheCountQueryPropagatesUnchanged() {
        staffOf(Role.VET, staff(21L, "Minh", Role.VET));
        IllegalStateException boom = new IllegalStateException("db down");
        when(assignments.countScheduledInWeek(anyCollection(), any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> service.listWeeklySummaries(MONDAY)).isSameAs(boom);
    }
}
