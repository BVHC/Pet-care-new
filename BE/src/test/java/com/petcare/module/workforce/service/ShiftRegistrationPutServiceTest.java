package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.PutShiftRegistrationRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationItem;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftRegistrationMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftRegistrationService#putMyShiftRegistration} (workforce-v1 #15, docs/adr/0138 mục "Đăng ký tuần — ghi #15"):
 * thứ tự {@code accounts} → hạn → ca {@code FOR SHARE} → phân ca → kiểm cả lô → định mức → ghi (hủy → Yêu cầu#5 → tạo)
 * → đọc response; mọi nhánh lỗi không ghi gì; phân ca {@code MANAGER} không bao giờ bị hủy nhưng được đếm và kiểm chồng
 * giờ; gửi lại cùng nội dung không ghi gì; lỗi ghi lan ra nguyên vẹn.
 */
class ShiftRegistrationPutServiceTest {

    private static final long STAFF = 12L;
    private static final long BRANCH = 5L;
    private static final int QUOTA = 3;
    /** 2026-11-23 là thứ Hai; hạn sửa là 00:00 giờ Việt Nam = 2026-11-22T17:00:00Z. */
    private static final LocalDate MON = LocalDate.of(2026, 11, 23);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate SUN = MON.plusDays(6);
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-22T17:00:00Z");

    /** Ca 1 sáng 08–12, ca 2 chiều 14–19, ca 3 giữa 11–15 (chồng cả hai), ca 4 liền kề 12–14, ca 5 đã ngừng dùng. */
    private final Map<Long, ShiftDefinition> defs = new HashMap<>();

    private final BranchScope branchScope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftDefinitionRepository definitions = mock(ShiftDefinitionRepository.class);
    private final ShiftRequestRepository requests = mock(ShiftRequestRepository.class);
    private final ShiftAssignmentService shiftAssignments = mock(ShiftAssignmentService.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final ShiftRegistrationMapper mapper = Mappers.getMapper(ShiftRegistrationMapper.class);
    private final ShiftAssignmentTransitionHandler transitions = new ShiftAssignmentTransitionHandler();

    private ShiftRegistrationService service = serviceAt(NOW);

    {
        defs.put(1L, definition(1L, BRANCH, true, "Ca sáng", 8, 12));
        defs.put(2L, definition(2L, BRANCH, true, "Ca chiều", 14, 19));
        defs.put(3L, definition(3L, BRANCH, true, "Ca giữa", 11, 15));
        defs.put(4L, definition(4L, BRANCH, true, "Ca liền kề", 12, 14));
        defs.put(5L, definition(5L, BRANCH, false, "Ca cũ", 8, 12));
        when(branchScope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(STAFF);
        when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.of(staff(Role.VET, true, BRANCH)));
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(QUOTA);
        // Chỉ trả ca thuộc chi nhánh được hỏi và có trong "defs" (ca id lạ / chi nhánh khác không có trong kết quả).
        when(definitions.lockForShareInBranch(anyCollection(), eq(BRANCH))).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(defs::get).filter(Objects::nonNull).toList();
        });
        when(definitions.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(defs::get).filter(Objects::nonNull).toList();
        });
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of());
        when(assignments.findScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of());
        when(shiftAssignments.toResponses(anyList())).thenReturn(List.of());
    }

    private ShiftRegistrationService serviceAt(Instant instant) {
        return new ShiftRegistrationService(branchScope, staffDirectory, assignments, definitions, requests,
                shiftAssignments, transitions, mapper, configs, Clock.fixed(instant, TimeConfig.BUSINESS_ZONE));
    }

    // ---- thứ tự

    @Test
    void locksAccountsThenShiftsThenAssignmentsThenReadsQuotaThenWritesCancelThenRejectThenCreateThenReadsBack() {
        ShiftAssignment keep = self(101L, MON, 1L);
        ShiftAssignment drop = self(102L, TUE, 1L);
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(keep, drop));
        List<ShiftAssignment> after = List.of(keep);
        when(assignments.findScheduledInWeek(STAFF, MON, SUN)).thenReturn(after);
        List<ShiftAssignmentResponse> list = List.of(response(101L));
        when(shiftAssignments.toResponses(after)).thenReturn(list);

        ShiftRegistrationResponse result = service.putMyShiftRegistration(MON,
                request(item(MON, 1L), item(WED, 1L), item(WED, 2L)));

        InOrder order = inOrder(branchScope, staffDirectory, definitions, assignments, configs, requests,
                shiftAssignments);
        order.verify(branchScope).current();
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(definitions).lockForShareInBranch(new TreeSet<>(List.of(1L, 2L)), BRANCH);
        order.verify(assignments).lockScheduledInWeek(STAFF, MON, SUN);
        order.verify(configs).getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
        order.verify(assignments).saveAllAndFlush(List.of(drop));
        order.verify(requests).rejectPendingBySystem(List.of(102L), ShiftRegistrationService.PENDING_REQUEST_EDITED_NOTE,
                NOW);
        order.verify(assignments).saveAllAndFlush(anyList());
        order.verify(assignments).findScheduledInWeek(STAFF, MON, SUN);
        order.verify(shiftAssignments).toResponses(after);
        assertThat(result).isEqualTo(new ShiftRegistrationResponse(MON, QUOTA, 1, DEADLINE, list));
    }

    @Test
    void theQuotaIsReadExactlyOnceAndTheSameValueIsReturned() {
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(2);

        ShiftRegistrationResponse result = service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L)));

        verify(configs, times(1)).getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
        assertThat(result.requiredShifts()).isEqualTo(2);
    }

    // ---- xác thực / phân quyền dưới khóa

    @Test
    void aWeekStartThatIsNotMondayIsBrNs11BeforeAnyLock() {
        assertThatThrownBy(() -> service.putMyShiftRegistration(TUE, request(item(TUE, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getRuleId()).isEqualTo("BR-NS-11"));

        verifyNoInteractions(staffDirectory, definitions, assignments, requests);
    }

    @Test
    void anYearOutsideTheWindowIsBrNs11BeforeAnyLock() {
        LocalDate monday2101 = LocalDate.of(2101, 1, 3);
        assertThat(monday2101.getDayOfWeek().getValue()).isEqualTo(1);

        assertThatThrownBy(() -> service.putMyShiftRegistration(monday2101, request()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verifyNoInteractions(staffDirectory, definitions, assignments, requests);
    }

    @Test
    void aStaffMemberWhoIsNoLongerSchedulableIsForbiddenAndNothingElseIsTouched() {
        for (Optional<StaffSummary> reread : List.of(Optional.of(staff(Role.BRANCH_MANAGER, true, BRANCH)),
                Optional.of(staff(Role.RECEPTIONIST, false, BRANCH)), Optional.of(staff(Role.CARETAKER, true, null)),
                Optional.<StaffSummary>empty())) {
            when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(reread);

            assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L))))
                    .isInstanceOf(AccessDeniedScopeException.class);
        }

        verifyNoInteractions(definitions, assignments, requests, configs);
    }

    @Test
    void theBranchOfNewAssignmentsIsTheOneReadUnderTheLockAndTheStaffIdIsThePrincipal() {
        when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.of(staff(Role.VET, true, 9L)));
        when(definitions.lockForShareInBranch(anyCollection(), eq(9L))).thenReturn(List.of(defs.get(1L)));

        service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L), item(WED, 1L)));

        ArgumentCaptor<List<ShiftAssignment>> created = captor();
        verify(assignments).saveAllAndFlush(created.capture());
        assertThat(created.getValue()).hasSize(3).allSatisfy(row -> {
            assertThat(row.getBranchId()).isEqualTo(9L);
            assertThat(row.getStaffId()).isEqualTo(STAFF);
            assertThat(row.getSource()).isEqualTo(ShiftAssignmentSource.SELF);
            assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        });
    }

    // ---- hạn

    @Test
    void theDeadlineIsExclusiveAtMidnightMondayVietnamTimeAndReadAfterTheLock() {
        assertThat(DEADLINE).isEqualTo(ShiftRegistrationService.editableUntil(MON));

        ShiftRegistrationService onTime = serviceAt(DEADLINE.minusNanos(1));
        assertThat(onTime.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L), item(WED, 1L))).weekStart())
                .isEqualTo(MON);

        ShiftRegistrationService late = serviceAt(DEADLINE);
        assertThatThrownBy(() -> late.putMyShiftRegistration(MON, request(item(MON, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-NS-11");
                    assertThat(ex.getMessage()).contains("quá hạn");
                });
    }

    @Test
    void anExpiredWeekOnlyReportsTheDeadlineNotEveryItemAndWritesNothing() {
        ShiftRegistrationService late = serviceAt(DEADLINE.plusSeconds(60));

        assertThatThrownBy(() -> late.putMyShiftRegistration(MON, request(item(SUN.plusDays(5), 99L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-NS-11");
                    assertThat(ex.getMessage()).contains("quá hạn").doesNotContain("Mục 1");
                });

        verify(staffDirectory).findStaffForUpdate(STAFF);
        verifyNoInteractions(definitions, requests);
        verifyErrorLeavesNoTrace();
    }

    // ---- kiểm cả lô

    @Test
    void everyItemErrorIsCollectedIntoOneBrNs11MessageAndNothingIsWritten() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(manager(201L, WED, 2L)));

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(
                item(MON, 1L),                    // 1 hợp lệ
                item(MON.minusDays(1), 1L),       // 2 ngoài tuần
                item(TUE, 99L),                   // 3 ca không tồn tại / chi nhánh khác
                item(TUE, 5L),                    // 4 ca đã ngừng dùng
                item(MON, 1L),                    // 5 trùng mục 1
                item(WED, 3L))))                  // 6 chồng giờ với phân ca MANAGER (ca chiều 14–19 vs ca giữa 11–15)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-NS-11");
                    assertThat(ex.getMessage())
                            .contains("Mục 2: ngày 22/11/2026 nằm ngoài tuần 23/11/2026 – 29/11/2026")
                            .contains("Mục 3: ca #99 không tồn tại hoặc không thuộc chi nhánh của bạn")
                            .contains("Mục 4: ca 'Ca cũ' đã ngừng dùng")
                            .contains("Mục 5: trùng với mục 1 (cùng ngày, cùng ca)")
                            .contains("Mục 6: chồng giờ với phân ca do quản lý xếp ngày 25/11/2026: Ca chiều (14:00–19:00)");
                });

        verifyErrorLeavesNoTrace();
    }

    @Test
    void twoItemsOfTheSameDayThatOverlapAreReportedButAdjacentOnesAreNot() {
        assertThatThrownBy(() -> service.putMyShiftRegistration(MON,
                request(item(MON, 1L), item(MON, 3L), item(MON, 2L), item(TUE, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getMessage())
                            .contains("Mục 1 và mục 2 chồng giờ ngày 23/11/2026: Ca sáng (08:00–12:00) / Ca giữa (11:00–15:00)")
                            .contains("Mục 2 và mục 3 chồng giờ ngày 23/11/2026: Ca giữa (11:00–15:00) / Ca chiều (14:00–19:00)")
                            .doesNotContain("Mục 1 và mục 3");
                });

        // Ca liền kề (12:00 = giờ kết thúc ca sáng, giờ bắt đầu ca 4) không chồng.
        assertThat(service.putMyShiftRegistration(MON, request(item(MON, 1L), item(MON, 4L), item(MON, 2L))).weekStart())
                .isEqualTo(MON);
    }

    @Test
    void anItemOnTheSamePairAsAManagerAssignmentIsAnOverlapAndTheManagerRowIsNeverCancelled() {
        ShiftAssignment managed = manager(201L, MON, 1L);
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(managed));

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getMessage()).contains("Mục 1: chồng giờ với phân ca do quản lý xếp"));

        verifyErrorLeavesNoTrace();
        assertThat(managed.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
    }

    @Test
    void theHoursOfAnExistingManagerAssignmentCoveringAShiftNotInTheBatchAreReadWithoutALock() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(manager(201L, TUE, 3L)));

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(TUE, 1L), item(MON, 1L), item(WED, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getMessage()).contains("Mục 1: chồng giờ với phân ca do quản lý xếp ngày 24/11/2026: Ca giữa"));

        verify(definitions).findAllById(new TreeSet<>(List.of(3L)));
    }

    @Test
    void aManagerAssignmentWhoseShiftCannotBeLoadedIsADataErrorNotASilentPass() {
        ShiftAssignment orphan = manager(201L, TUE, 777L);
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(TUE, 1L), item(MON, 1L), item(WED, 1L))))
                .isInstanceOf(IllegalStateException.class);

        verifyErrorLeavesNoTrace();
    }

    // ---- định mức

    @Test
    void exactlyTheQuotaIsAcceptedOneBelowIsBrNs12WithTheNumbers() {
        assertThat(service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L), item(WED, 1L))).weekStart())
                .isEqualTo(MON);

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-NS-12");
                    assertThat(ex.getMessage()).contains("có 2 ca (gồm 0 ca do quản lý xếp)")
                            .contains("tối thiểu 3 ca").contains("còn thiếu 1");
                });
    }

    @Test
    void anEmptyListIsStillCheckedAgainstTheQuota() {
        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-NS-12");
                    assertThat(ex.getMessage()).contains("còn thiếu 3");
                });

        verify(definitions, never()).lockForShareInBranch(anyCollection(), anyLong());
        verifyErrorLeavesNoTrace();
    }

    @Test
    void managerAssignmentsCountTowardsTheQuota() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN))
                .thenReturn(List.of(manager(201L, WED, 1L), manager(202L, SUN, 1L)));

        // 1 mục SELF + 2 MANAGER = 3 = định mức.
        assertThat(service.putMyShiftRegistration(MON, request(item(MON, 1L))).weekStart()).isEqualTo(MON);
        // 0 mục SELF + 2 MANAGER = 2 < 3.
        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getMessage()).contains("có 2 ca (gồm 2 ca do quản lý xếp)"));
    }

    @Test
    void anItemErrorWinsOverAMissingQuotaAndTheQuotaIsNotEvenRead() {
        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 99L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getRuleId()).isEqualTo("BR-NS-11"));

        verify(configs, never()).getInt(any());
    }

    @Test
    void theQuotaIsReadFromConfigOnEveryCall() {
        when(configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS)).thenReturn(1, 2);

        assertThat(service.putMyShiftRegistration(MON, request(item(MON, 1L))).requiredShifts()).isEqualTo(1);
        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getRuleId()).isEqualTo("BR-NS-12"));
    }

    // ---- phần khác biệt

    @Test
    void cancelsOnlyTheDroppedSelfRowsAndCreatesOnlyTheNewPairs() {
        ShiftAssignment keep = self(101L, TUE, 1L);
        ShiftAssignment drop = self(102L, MON, 1L);
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(drop, keep));

        service.putMyShiftRegistration(MON, request(item(TUE, 1L), item(WED, 1L), item(WED, 2L)));

        ArgumentCaptor<List<ShiftAssignment>> saved = captor();
        verify(assignments, times(2)).saveAllAndFlush(saved.capture());
        List<ShiftAssignment> cancelled = saved.getAllValues().get(0);
        List<ShiftAssignment> created = saved.getAllValues().get(1);

        assertThat(cancelled).containsExactly(drop);
        assertThat(drop.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
        assertThat(drop.getReason()).isEqualTo("Nhân viên sửa đăng ký tuần");
        assertThat(drop.getStatusChangedBy()).isEqualTo(STAFF);
        assertThat(drop.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(keep.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(keep.getStatusChangedAt()).isNull();

        assertThat(created).extracting(ShiftAssignment::getWorkDate, ShiftAssignment::getShiftDefinitionId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(WED, 1L),
                        org.assertj.core.groups.Tuple.tuple(WED, 2L));
        verify(requests).rejectPendingBySystem(List.of(102L), ShiftRegistrationService.PENDING_REQUEST_EDITED_NOTE, NOW);
    }

    @Test
    void resendingTheSameContentWritesNothingAtAll() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(
                List.of(self(101L, MON, 1L), self(102L, TUE, 1L), self(103L, WED, 1L)));

        service.putMyShiftRegistration(MON, request(item(WED, 1L), item(MON, 1L), item(TUE, 1L)));

        verifyNothingWritten();
        verifyNoInteractions(requests);
        verify(assignments, times(1)).findScheduledInWeek(STAFF, MON, SUN);
    }

    @Test
    void aManagerRowIsNeverCancelledEvenWhenTheListNoLongerMentionsIt() {
        ShiftAssignment managed = manager(201L, SUN, 1L);
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(managed));

        service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L)));

        assertThat(managed.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        ArgumentCaptor<List<ShiftAssignment>> saved = captor();
        verify(assignments, times(1)).saveAllAndFlush(saved.capture());
        assertThat(saved.getValue()).hasSize(2).noneMatch(row -> row == managed);
        verifyNoInteractions(requests);
    }

    @Test
    void onlyCancellationsNeverCreateAndOnlyCreationsNeverRejectRequests() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(
                List.of(self(101L, MON, 1L), self(102L, TUE, 1L), self(103L, WED, 1L), self(104L, WED, 2L)));

        service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L), item(WED, 1L)));

        verify(assignments, times(1)).saveAllAndFlush(anyList());
        verify(requests).rejectPendingBySystem(List.of(104L), ShiftRegistrationService.PENDING_REQUEST_EDITED_NOTE, NOW);
    }

    // ---- lỗi ghi không bị nuốt

    @Test
    void aUniqueViolationWhileCreatingPropagatesUntouchedAndTheResponseIsNeverBuilt() {
        DataIntegrityViolationException conflict = new DataIntegrityViolationException("ux_shift_assignments_scheduled");
        doAnswer(invocation -> {
            throw conflict;
        }).when(assignments).saveAllAndFlush(anyList());

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L), item(TUE, 1L), item(WED, 1L))))
                .isSameAs(conflict);

        verify(assignments, never()).findScheduledInWeek(anyLong(), any(), any());
        verifyNoInteractions(shiftAssignments);
    }

    @Test
    void aFailureWhileRejectingRequestsPropagatesAndNothingIsCreated() {
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenReturn(List.of(self(101L, MON, 1L)));
        IllegalStateException failure = new IllegalStateException("DB");
        when(requests.rejectPendingBySystem(anyList(), anyString(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(TUE, 1L), item(WED, 1L), item(SUN, 1L))))
                .isSameAs(failure);

        verify(assignments, times(1)).saveAllAndFlush(anyList());
    }

    @Test
    void aLockFailureStopsEverythingAfterIt() {
        IllegalStateException failure = new IllegalStateException("lock");
        when(assignments.lockScheduledInWeek(STAFF, MON, SUN)).thenThrow(failure);

        assertThatThrownBy(() -> service.putMyShiftRegistration(MON, request(item(MON, 1L)))).isSameAs(failure);

        verify(configs, never()).getInt(any());
        verifyErrorLeavesNoTrace();
    }

    // ---- ranh giới transaction

    @Test
    void thePutIsOneDefaultTransactionAndTheReadStaysReadOnlyRepeatableRead() throws NoSuchMethodException {
        Method put = ShiftRegistrationService.class.getMethod("putMyShiftRegistration", LocalDate.class,
                PutShiftRegistrationRequest.class);
        Transactional write = put.getAnnotation(Transactional.class);
        assertThat(write).isNotNull();
        assertThat(write.readOnly()).isFalse();
        assertThat(write.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(write.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(write.rollbackFor()).isEmpty();
        assertThat(write.noRollbackFor()).isEmpty();

        Method get = ShiftRegistrationService.class.getMethod("getMyShiftRegistration", LocalDate.class);
        Transactional read = get.getAnnotation(Transactional.class);
        assertThat(read.readOnly()).isTrue();
        assertThat(read.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(ShiftRegistrationService.class.getAnnotation(Transactional.class)).isNull();
    }

    // ---- tiện ích

    /** Không có lần ghi nào (hủy, từ chối yêu cầu, tạo). */
    private void verifyNothingWritten() {
        verify(assignments, never()).saveAllAndFlush(anyList());
        verify(assignments, never()).save(any());
        verify(assignments, never()).saveAndFlush(any());
        verify(requests, never()).rejectPendingBySystem(anyList(), anyString(), any());
    }

    /** Nhánh lỗi: không ghi và cũng không dựng response. */
    private void verifyErrorLeavesNoTrace() {
        verifyNothingWritten();
        verify(assignments, never()).findScheduledInWeek(anyLong(), any(), any());
        verifyNoMoreInteractions(shiftAssignments);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ShiftAssignment>> captor() {
        return ArgumentCaptor.forClass((Class<List<ShiftAssignment>>) (Class<?>) List.class);
    }

    private static PutShiftRegistrationRequest request(ShiftRegistrationItem... items) {
        return new PutShiftRegistrationRequest(new ArrayList<>(List.of(items)));
    }

    private static ShiftRegistrationItem item(LocalDate day, long definitionId) {
        return new ShiftRegistrationItem(day, definitionId);
    }

    private static StaffSummary staff(Role role, boolean active, Long branchId) {
        return new StaffSummary(STAFF, "An", role, branchId, active, false);
    }

    private static ShiftDefinition definition(long id, long branchId, boolean active, String name, int startHour,
            int endHour) {
        ShiftDefinition definition = ShiftDefinition.create(branchId, name, LocalTime.of(startHour, 0),
                LocalTime.of(endHour, 0));
        ReflectionTestUtils.setField(definition, "id", id);
        if (!active) {
            definition.deactivate();
        }
        return definition;
    }

    private static ShiftAssignment self(long id, LocalDate day, long definitionId) {
        return row(id, day, definitionId, ShiftAssignmentSource.SELF);
    }

    private static ShiftAssignment manager(long id, LocalDate day, long definitionId) {
        return row(id, day, definitionId, ShiftAssignmentSource.MANAGER);
    }

    private static ShiftAssignment row(long id, LocalDate day, long definitionId, ShiftAssignmentSource source) {
        ShiftAssignment row = ShiftAssignment.schedule(BRANCH, STAFF, definitionId, day, source);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private static ShiftAssignmentResponse response(long id) {
        return new ShiftAssignmentResponse(id, BRANCH, STAFF, "An", 1L, "Ca sáng", LocalTime.of(8, 0),
                LocalTime.of(12, 0), MON, ShiftAssignmentStatus.SCHEDULED, ShiftAssignmentSource.SELF, null, null);
    }
}
