package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftReasonRequest;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.AssignmentScope;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.PendingRequestRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftAssignmentService} (workforce-v1 #4–5, docs/adr/0138 bước 3): thứ tự kiểm không khóa → ngày → khóa nhân
 * viên → khóa ca → kiểm → ghi → audit → map; mọi nhánh lỗi không ghi và không audit; ghép danh sách theo lô; cấu hình
 * transaction của hai use case.
 */
class ShiftAssignmentServiceTest {

    private static final long BRANCH = 5L;
    private static final long OTHER_BRANCH = 6L;
    private static final long STAFF = 12L;
    private static final long DEFINITION = 7L;
    private static final long ASSIGNMENT = 31L;
    private static final long MANAGER = 99L;
    private static final long REQUEST = 900L;
    private static final int ABSENCE_MAX_DAYS = 7;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate WORK_DAY = LocalDate.of(2026, 11, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final ShiftDefinitionRepository definitions = mock(ShiftDefinitionRepository.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftRequestRepository requests = mock(ShiftRequestRepository.class);
    private final ShiftAssignmentMapper mapper = spy(Mappers.getMapper(ShiftAssignmentMapper.class));
    private final ShiftAssignmentTransitionHandler transitions = new ShiftAssignmentTransitionHandler();
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);

    private ShiftAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new ShiftAssignmentService(branchScope, staffDirectory, definitions, assignments, requests, mapper,
                transitions, notifications, branches, audit, configs, CLOCK);
        when(configs.getInt(ConfigKey.WORKFORCE_ABSENCE_REPORT_MAX_DAYS)).thenReturn(ABSENCE_MAX_DAYS);
        when(branchScope.current()).thenReturn(principal);
        when(principal.branchId()).thenReturn(BRANCH);
        when(principal.accountId()).thenReturn(MANAGER);
        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, BRANCH, true)));
        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, BRANCH, true)));
        when(definitions.findBranchIdById(DEFINITION)).thenReturn(Optional.of(BRANCH));
        when(definitions.findByIdForShare(DEFINITION)).thenReturn(Optional.of(definition(DEFINITION, BRANCH, true)));
        when(assignments.findOverlapping(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(assignments.saveAndFlush(any(ShiftAssignment.class))).thenAnswer(call -> {
            ShiftAssignment saved = call.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", ASSIGNMENT);
            }
            return saved;
        });
    }

    // ---------------------------------------------------------------- create: thứ tự và kết quả

    @Test
    void createChecksWithoutLockingThenLocksStaffThenDefinitionThenWritesThenAuditsThenMaps() {
        ShiftAssignmentResponse response = service.createShiftAssignment(request(WORK_DAY));

        InOrder order = inOrder(branchScope, staffDirectory, definitions, assignments, mapper, audit);
        order.verify(branchScope).current();
        order.verify(staffDirectory).findStaff(STAFF);
        order.verify(definitions).findBranchIdById(DEFINITION);
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(definitions).findByIdForShare(DEFINITION);
        order.verify(assignments).findOverlapping(STAFF, WORK_DAY, LocalTime.of(8, 0), LocalTime.of(12, 0));
        order.verify(assignments).saveAndFlush(any(ShiftAssignment.class));
        order.verify(mapper).toSnapshot(any(ShiftAssignment.class));
        order.verify(audit).record(any(AuditEntry.class));
        order.verify(mapper).toResponse(any(ShiftAssignment.class), any(), any(ShiftDefinition.class), any());
        assertThat(response).isEqualTo(new ShiftAssignmentResponse(ASSIGNMENT, BRANCH, STAFF, "Nhân viên 12", DEFINITION,
                "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), WORK_DAY, ShiftAssignmentStatus.SCHEDULED,
                ShiftAssignmentSource.MANAGER, null, null));
    }

    @Test
    void createWritesAManagerSourcedScheduledRowInTheCallersBranchAndAuditsTheNewValuesOnly() {
        service.createShiftAssignment(request(WORK_DAY));

        ArgumentCaptor<ShiftAssignment> saved = ArgumentCaptor.forClass(ShiftAssignment.class);
        verify(assignments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getValue().getStaffId()).isEqualTo(STAFF);
        assertThat(saved.getValue().getShiftDefinitionId()).isEqualTo(DEFINITION);
        assertThat(saved.getValue().getWorkDate()).isEqualTo(WORK_DAY);
        assertThat(saved.getValue().getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(saved.getValue().getSource()).isEqualTo(ShiftAssignmentSource.MANAGER);
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("SHIFT_ASSIGNMENT_CREATED");
        assertThat(entry.entityType()).isEqualTo("shift_assignments");
        assertThat(entry.entityId()).isEqualTo(ASSIGNMENT);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(new ShiftAssignmentAuditSnapshot(STAFF, DEFINITION, "2026-11-24",
                "SCHEDULED", "MANAGER", null));
    }

    @Test
    void todayIsAllowedButYesterdayIsNot() {
        service.createShiftAssignment(request(TODAY));
        verify(assignments).saveAndFlush(any(ShiftAssignment.class));

        assertThatThrownBy(() -> service.createShiftAssignment(request(TODAY.minusDays(1))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");
        verify(assignments, times(1)).saveAndFlush(any(ShiftAssignment.class));
    }

    @Test
    void allThreeSchedulableRolesAreAccepted() {
        for (Role role : List.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER)) {
            when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.of(staff(STAFF, role, BRANCH, true)));

            service.createShiftAssignment(request(WORK_DAY));
        }

        verify(assignments, times(3)).saveAndFlush(any(ShiftAssignment.class));
    }

    // ---------------------------------------------------------------- create: lỗi không ghi, không audit

    @Test
    void anUnknownOrForeignStaffMemberIs404BeforeAnyLockOrWrite() {
        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(staffDirectory.findStaff(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, OTHER_BRANCH, true)));
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.of(staff(STAFF, Role.SUPER_MANAGER, null, true)));
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        assertNothingLockedOrWritten();
    }

    @Test
    void anUnknownOrForeignShiftIs404BeforeAnyLockOrWrite() {
        when(definitions.findBranchIdById(DEFINITION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(definitions.findBranchIdById(DEFINITION)).thenReturn(Optional.of(OTHER_BRANCH));
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        assertNothingLockedOrWritten();
    }

    @Test
    void theTwoNotFoundCasesLookTheSameSoAForeignIdIsNotRevealed() {
        when(staffDirectory.findStaff(STAFF)).thenReturn(Optional.empty());
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShiftAssignment(request(WORK_DAY)));
        when(staffDirectory.findStaff(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, OTHER_BRANCH, true)));
        Throwable foreign = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.createShiftAssignment(request(WORK_DAY)));

        assertThat(foreign).isInstanceOf(ResourceNotFoundException.class).hasMessage(missing.getMessage());
    }

    @Test
    void aPastDateIsRejectedBeforeAnyLockIsTaken() {
        assertThatThrownBy(() -> service.createShiftAssignment(request(TODAY.minusDays(1))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");

        assertNothingLockedOrWritten();
    }

    @Test
    void aStaffMemberWhoMovedBranchBetweenTheCheckAndTheLockIs404AndNothingIsWritten() {
        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, OTHER_BRANCH, true)));

        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(definitions, never()).findByIdForShare(anyLong());
        verify(assignments, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aBranchManagerOrAnInactiveStaffMemberIsRejectedUnderTheLock() {
        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.BRANCH_MANAGER, BRANCH, true)));
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");

        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.VET, BRANCH, false)));
        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");

        verify(definitions, never()).findByIdForShare(anyLong());
        verify(assignments, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void anInactiveShiftIsRejectedAfterTheStaffLock() {
        when(definitions.findByIdForShare(DEFINITION)).thenReturn(Optional.of(definition(DEFINITION, BRANCH, false)));

        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");

        verify(assignments, never()).findOverlapping(anyLong(), any(), any(), any());
        verify(assignments, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void anOverlapIsRejectedAndTheMessageNamesTheClashingShifts() {
        when(assignments.findOverlapping(STAFF, WORK_DAY, LocalTime.of(8, 0), LocalTime.of(12, 0)))
                .thenReturn(List.of(overlap("Ca sớm", LocalTime.of(7, 0), LocalTime.of(9, 30))));

        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-NS-02").hasMessageContaining("Ca sớm (07:00–09:30)");

        verify(assignments, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void theOverlapQueryReceivesTheShiftsOwnHoursSoTouchingShiftsAreTheQuerysConcern() {
        when(definitions.findByIdForShare(DEFINITION)).thenReturn(Optional.of(
                definition(DEFINITION, BRANCH, true, "Ca chiều", LocalTime.of(12, 0), LocalTime.of(16, 0))));

        service.createShiftAssignment(request(WORK_DAY));

        verify(assignments).findOverlapping(STAFF, WORK_DAY, LocalTime.of(12, 0), LocalTime.of(16, 0));
    }

    @Test
    void aCallerWithoutABranchIsDeniedBeforeAnythingIsRead() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.createShiftAssignment(request(WORK_DAY)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(staffDirectory, definitions, assignments, audit);
    }

    // ---------------------------------------------------------------- list

    @Test
    void listRejectsAnInvertedOrTooLongRangeAndAcceptsExactly62Days() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        assertThatThrownBy(() -> service.listShiftAssignments(from, from.minusDays(1), null, null, 0, 20))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-03");
        assertThatThrownBy(() -> service.listShiftAssignments(from, from.plusDays(62), null, null, 0, 20))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-03");
        verify(assignments, never()).findBranchSchedule(any(), any(), any(), any(), any(), any());

        when(assignments.findBranchSchedule(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        service.listShiftAssignments(from, from.plusDays(61), null, null, 0, 20);

        verify(assignments).findBranchSchedule(any(), any(), any(), any(), any(), any());
    }

    @Test
    void listClampsPageAndSizeAndAlwaysFiltersByTheCallersBranch() {
        when(assignments.findBranchSchedule(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        LocalDate from = LocalDate.of(2026, 11, 23);

        service.listShiftAssignments(from, from.plusDays(6), 99L, ShiftAssignmentStatus.ABSENT, -3, 0);
        service.listShiftAssignments(from, from.plusDays(6), null, null, 2, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(assignments, times(2)).findBranchSchedule(any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getAllValues()).containsExactly(PageRequest.of(0, 1), PageRequest.of(2, 100));
        verify(assignments).findBranchSchedule(BRANCH, from, from.plusDays(6), 99L, ShiftAssignmentStatus.ABSENT,
                PageRequest.of(0, 1));
        verify(assignments).findBranchSchedule(BRANCH, from, from.plusDays(6), null, null, PageRequest.of(2, 100));
    }

    @Test
    void anEmptyPageTouchesNoOtherSource() {
        when(assignments.findBranchSchedule(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        PageResponse<ShiftAssignmentResponse> page = service.listShiftAssignments(WORK_DAY, WORK_DAY, null, null, 0, 20);

        assertThat(page.content()).isEmpty();
        verifyNoInteractions(staffDirectory, requests);
        verify(definitions, never()).findAllById(anyCollection());
    }

    @Test
    void aFullPageIsAssembledWithOneCallPerSourceAndCarriesThePendingRequest() {
        List<ShiftAssignment> rows = new ArrayList<>();
        List<StaffSummary> people = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            rows.add(assignment(i, 100 + (i % 10), i % 2 == 0 ? DEFINITION : 8L));
        }
        for (long staffId = 100; staffId < 110; staffId++) {
            people.add(staff(staffId, Role.VET, BRANCH, true));
        }
        when(assignments.findBranchSchedule(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 100), 250));
        when(staffDirectory.findStaffByIds(anyCollection())).thenReturn(people);
        when(definitions.findAllById(anyCollection())).thenReturn(List.of(definition(DEFINITION, BRANCH, true),
                definition(8L, BRANCH, true, "Ca chiều", LocalTime.of(13, 0), LocalTime.of(17, 0))));
        when(requests.findPendingByAssignmentIds(anyCollection())).thenReturn(List.of(pending(2L, 900L)));

        PageResponse<ShiftAssignmentResponse> page = service.listShiftAssignments(WORK_DAY, WORK_DAY, null, null, 0, 100);

        verify(staffDirectory, times(1)).findStaffByIds(anyCollection());
        verify(definitions, times(1)).findAllById(anyCollection());
        verify(requests, times(1)).findPendingByAssignmentIds(anyCollection());
        assertThat(page.content()).hasSize(100);
        assertThat(page.totalElements()).isEqualTo(250);
        assertThat(page.content().get(1).pendingRequestId()).isEqualTo(900L);
        assertThat(page.content().get(0).pendingRequestId()).isNull();
        assertThat(page.content().get(1).shiftName()).isEqualTo("Ca sáng");
        assertThat(page.content().get(0).shiftName()).isEqualTo("Ca chiều");
        assertThat(page.content().get(0).staffName()).isEqualTo("Nhân viên 101");
    }

    @Test
    void aMissingStaffMemberIsADataErrorNotANullName() {
        when(assignments.findBranchSchedule(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(assignment(1L, 100L, DEFINITION)), PageRequest.of(0, 20), 1));
        when(staffDirectory.findStaffByIds(anyCollection())).thenReturn(List.of());
        when(definitions.findAllById(anyCollection())).thenReturn(List.of(definition(DEFINITION, BRANCH, true)));
        when(requests.findPendingByAssignmentIds(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> service.listShiftAssignments(WORK_DAY, WORK_DAY, null, null, 0, 20))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- cấu hình transaction (docs/adr/0138)

    @Test
    void createRunsInAWriteTransactionAtReadCommittedSoTheOverlapCheckSeesWhatCommittedWhileItWaited() throws Exception {
        Transactional create = ShiftAssignmentService.class.getMethod("createShiftAssignment",
                CreateShiftAssignmentRequest.class).getAnnotation(Transactional.class);

        assertThat(create.readOnly()).isFalse();
        assertThat(create.isolation()).as("không đặt isolation: dùng READ COMMITTED mặc định của DB")
                .isEqualTo(Isolation.DEFAULT);
    }

    @Test
    void listIsReadOnlyAtRepeatableReadSoPageTotalAndJoinedDataShareOneSnapshot() throws Exception {
        Transactional list = ShiftAssignmentService.class.getMethod("listShiftAssignments", LocalDate.class,
                LocalDate.class, Long.class, ShiftAssignmentStatus.class, int.class, int.class)
                .getAnnotation(Transactional.class);

        assertThat(list.readOnly()).isTrue();
        assertThat(list.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    // ---------------------------------------------------------------- cancel (#6): thứ tự và kết quả

    @Test
    void cancelChecksScopeBeforeAnyLockThenLocksAccountsAssignmentRequestThenWritesAuditsAndNotifies() {
        givenAssignment(WORK_DAY);

        ShiftAssignmentResponse response = service.cancelShiftAssignment(ASSIGNMENT, reason("  Quản lý đổi lịch  "));

        InOrder order = inOrder(branchScope, staffDirectory, definitions, assignments, requests, audit, notifications);
        order.verify(assignments).findScopeById(ASSIGNMENT);
        order.verify(branchScope).check(BRANCH);
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
        order.verify(requests).findPendingByAssignmentIdForUpdate(ASSIGNMENT);
        order.verify(definitions).findById(DEFINITION);
        order.verify(assignments).saveAndFlush(any(ShiftAssignment.class));
        order.verify(audit).record(any(AuditEntry.class));
        order.verify(notifications, times(2)).enqueue(any(NotificationRequest.class));
        assertThat(response).isEqualTo(new ShiftAssignmentResponse(ASSIGNMENT, BRANCH, STAFF, "Nhân viên 12",
                DEFINITION, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), WORK_DAY,
                ShiftAssignmentStatus.CANCELLED, ShiftAssignmentSource.SELF, "Quản lý đổi lịch", null));
    }

    @Test
    void cancelRecordsTheManagerAndTheInstantAndAuditsBeforeAndAfterWithTheStrippedReason() {
        ShiftAssignment row = givenAssignment(WORK_DAY);

        service.cancelShiftAssignment(ASSIGNMENT, reason("  Quản lý đổi lịch  "));

        assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
        assertThat(row.getReason()).isEqualTo("Quản lý đổi lịch");
        assertThat(row.getStatusChangedBy()).as("người thực hiện lấy từ principal").isEqualTo(MANAGER);
        assertThat(row.getStatusChangedAt()).isEqualTo(CLOCK.instant());
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("SHIFT_ASSIGNMENT_CANCELLED");
        assertThat(entry.entityType()).isEqualTo("shift_assignments");
        assertThat(entry.entityId()).isEqualTo(ASSIGNMENT);
        assertThat(entry.before()).as("chụp trước khi đổi").isEqualTo(new ShiftAssignmentAuditSnapshot(STAFF,
                DEFINITION, "2026-11-24", "SCHEDULED", "SELF", null));
        assertThat(entry.after()).isEqualTo(new ShiftAssignmentAuditSnapshot(STAFF, DEFINITION, "2026-11-24",
                "CANCELLED", "SELF", "Quản lý đổi lịch"));
        assertThat(entry.reason()).isEqualTo("Quản lý đổi lịch");
    }

    @Test
    void cancelRejectsThePendingRequestBySystemInTheSameInstantAndWithoutADecider() {
        ShiftAssignment row = givenAssignment(WORK_DAY);
        ShiftRequest pending = pendingRequest();
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(pending));

        service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));

        assertThat(pending.getStatus()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(pending.getDecidedBy()).as("hệ thống tự từ chối, không có người quyết định").isNull();
        assertThat(pending.getDecisionNote()).isEqualTo(ShiftAssignmentService.PENDING_REQUEST_CLOSED_NOTE);
        assertThat(pending.getClosedAt()).isEqualTo(row.getStatusChangedAt()).isEqualTo(CLOCK.instant());
        verify(requests).saveAndFlush(pending);
    }

    @Test
    void cancelWithoutAPendingRequestWritesNoRequestRow() {
        givenAssignment(WORK_DAY);

        service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));

        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
    }

    @Test
    void cancelEnqueuesInAppThenEmailWithTheStrippedReasonAndTheCurrentShift() {
        givenAssignment(WORK_DAY);

        service.cancelShiftAssignment(ASSIGNMENT, reason("  Nghỉ lễ  "));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(captor.capture());
        NotificationRequest inApp = captor.getAllValues().get(0);
        NotificationRequest email = captor.getAllValues().get(1);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).as("tới nhân viên của phân ca, không phải quản lý").isEqualTo(STAFF);
        assertThat(inApp.payload()).containsEntry("ly_do", "Nghỉ lễ").containsEntry("ten_ca", "Ca sáng")
                .containsEntry("ngay_lam", "24/11/2026").containsEntry("gio_bat_dau", "08:00")
                .containsEntry("gio_ket_thuc", "12:00").containsEntry("chi_nhanh", "Chi nhánh Quận 1");
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ASSIGNMENT_CANCELLED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.payload()).containsEntry("ten_nhan_vien", "Nhân viên 12");
    }

    @Test
    void aStaffMemberWithoutAnEmailGetsOnlyTheInAppNoticeAndTheCancelStillSucceeds() {
        givenAssignment(WORK_DAY);
        when(staffDirectory.findEmail(STAFF)).thenReturn(Optional.empty());

        service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void aLockedOrDisabledStaffMemberStillGetsTheNotice() {
        givenAssignment(WORK_DAY);
        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.VET, BRANCH, false)));

        service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));

        verify(notifications, times(2)).enqueue(any(NotificationRequest.class));
    }

    @Test
    void aShiftThatStartsLaterTodayCanStillBeCancelled() {
        ShiftAssignment row = givenAssignment(TODAY);
        when(definitions.findById(DEFINITION)).thenReturn(Optional.of(definition(DEFINITION, BRANCH, true, "Ca chiều",
                LocalTime.of(13, 0), LocalTime.of(17, 0))));

        service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));

        assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
    }

    @Test
    void theStartInstantItselfCountsAsStartedAndOneMinuteBeforeDoesNot() {
        givenAssignment(TODAY);
        Clock atStart = Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), TimeConfig.BUSINESS_ZONE); // 08:00 VN
        Clock oneMinuteBefore = Clock.fixed(Instant.parse("2026-11-17T00:59:00Z"), TimeConfig.BUSINESS_ZONE);

        assertThatThrownBy(() -> serviceAt(atStart).cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");
        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));

        ShiftAssignment row = givenAssignment(TODAY);
        serviceAt(oneMinuteBefore).cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ"));
        assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
    }

    @Test
    void hasStartedIsFalseBeforeTrueAtAndTrueAfterTheStartInTheBusinessZoneAndFollowsTheDayNotTheUtcDate() {
        LocalDate day = LocalDate.of(2026, 11, 17);
        LocalTime eight = LocalTime.of(8, 0);

        // 08:00 giờ Việt Nam = 01:00 UTC cùng ngày.
        assertThat(ShiftAssignmentService.hasStarted(Instant.parse("2026-11-17T00:59:59Z"), day, eight)).isFalse();
        assertThat(ShiftAssignmentService.hasStarted(Instant.parse("2026-11-17T01:00:00Z"), day, eight)).isTrue();
        assertThat(ShiftAssignmentService.hasStarted(Instant.parse("2026-11-17T01:00:01Z"), day, eight)).isTrue();
        // 00:30 ngày 17 giờ Việt Nam vẫn là 16/11 UTC: ca 08:00 ngày 17 chưa bắt đầu; ca của ngày 16 thì đã qua.
        assertThat(ShiftAssignmentService.hasStarted(Instant.parse("2026-11-16T17:30:00Z"), day, eight)).isFalse();
        assertThat(ShiftAssignmentService.hasStarted(Instant.parse("2026-11-16T17:30:00Z"), day.minusDays(1), eight))
                .isTrue();
    }

    // ---------------------------------------------------------------- cancel (#6): lỗi không ghi, không audit

    @Test
    void cancelOfAnUnknownAssignmentIs404BeforeAnyLockOrWrite() {
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertCancelLockedOrWrittenNothing();
    }

    @Test
    void cancelOfAnotherBranchsAssignmentIs403AndNeverLocksThatStaffAccount() {
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.of(scope(STAFF, OTHER_BRANCH)));
        org.mockito.Mockito.doThrow(new AccessDeniedScopeException("branch:" + OTHER_BRANCH, "branch:" + BRANCH))
                .when(branchScope).check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(AccessDeniedScopeException.class);

        assertCancelLockedOrWrittenNothing();
    }

    @Test
    void aCallerWithoutABranchIsRefusedBeforeEvenReadingTheAssignment() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(assignments, never()).findScopeById(anyLong());
        assertCancelLockedOrWrittenNothing();
    }

    @Test
    void everyFinalStatusIs409EvenWhenTheShiftIsAlreadyOverSoStateBeatsTheStartedGuard() {
        for (ShiftAssignmentStatus finalStatus : List.of(ShiftAssignmentStatus.CANCELLED,
                ShiftAssignmentStatus.ON_LEAVE, ShiftAssignmentStatus.ABSENT)) {
            ShiftAssignment row = givenAssignment(TODAY.minusDays(3)); // ca đã qua
            switch (finalStatus) {
                case CANCELLED -> row.cancel("Đã hủy", MANAGER, CLOCK.instant());
                case ON_LEAVE -> row.markOnLeave("Nghỉ phép", MANAGER, CLOCK.instant());
                case ABSENT -> row.markAbsent("Vắng", MANAGER, CLOCK.instant());
                default -> throw new IllegalStateException();
            }

            assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                    .as(finalStatus.name()).isInstanceOf(InvalidStateTransitionException.class);
        }

        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
        verifyNoInteractions(audit, notifications);
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
    }

    @Test
    void aShiftThatAlreadyStartedIs400AndWritesNothing() {
        givenAssignment(TODAY); // ca 08:00, bây giờ 10:00

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-02");

        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
        verifyNoInteractions(audit, notifications);
    }

    @Test
    void aStartedShiftKeepsItsPendingRequestUntouched() {
        givenAssignment(TODAY);
        ShiftRequest pending = pendingRequest();
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(pending.getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
    }

    @Test
    void anAssignmentPointingAtANonStaffAccountIsADataErrorNotA404() {
        givenAssignment(WORK_DAY);
        when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(IllegalStateException.class);

        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
    }

    @Test
    void aMissingBranchIsADataErrorAfterTheWriteSoTheTransactionRollsBackEverything() {
        givenAssignment(WORK_DAY);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelShiftAssignment(ASSIGNMENT, reason("Nghỉ lễ")))
                .isInstanceOf(IllegalStateException.class);

        verify(notifications, never()).enqueue(any(NotificationRequest.class));
    }

    // ---------------------------------------------------------------- my schedule (#8)

    @Test
    void myScheduleReadsTheCallersOwnRowsOnlyWhateverTheBranchAndNeverChecksScope() {
        ShiftAssignment row = assignment(1L, MANAGER, DEFINITION);
        when(assignments.findStaffSchedule(MANAGER, WORK_DAY, WORK_DAY.plusDays(6))).thenReturn(List.of(row));
        when(staffDirectory.findStaffByIds(anyCollection())).thenReturn(List.of(staff(MANAGER, Role.VET, BRANCH, true)));
        when(definitions.findAllById(anyCollection())).thenReturn(List.of(definition(DEFINITION, BRANCH, true)));
        when(requests.findPendingByAssignmentIds(anyCollection())).thenReturn(List.of(pending(1L, REQUEST)));

        List<ShiftAssignmentResponse> result = service.listMyShiftAssignments(WORK_DAY, WORK_DAY.plusDays(6));

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.staffId()).isEqualTo(MANAGER);
            assertThat(response.staffName()).isEqualTo("Nhân viên 99");
            assertThat(response.pendingRequestId()).isEqualTo(REQUEST);
        });
        verify(assignments, never()).findBranchSchedule(any(), any(), any(), any(), any(), any());
        verify(branchScope, never()).check(any());
        verify(branchScope, never()).resolve(any());
    }

    @Test
    void myScheduleOfNothingReturnsAnEmptyListWithoutBatchLookups() {
        when(assignments.findStaffSchedule(anyLong(), any(), any())).thenReturn(List.of());

        assertThat(service.listMyShiftAssignments(WORK_DAY, WORK_DAY)).isEmpty();

        verifyNoInteractions(requests);
        verify(staffDirectory, never()).findStaffByIds(anyCollection());
    }

    @Test
    void myScheduleRangeRulesAreTheSameAsTheBranchList() {
        assertThatThrownBy(() -> service.listMyShiftAssignments(WORK_DAY, WORK_DAY.minusDays(1)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-03");
        assertThatThrownBy(() -> service.listMyShiftAssignments(WORK_DAY, WORK_DAY.plusDays(62)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-03");
        when(assignments.findStaffSchedule(anyLong(), any(), any())).thenReturn(List.of());

        assertThat(service.listMyShiftAssignments(WORK_DAY, WORK_DAY.plusDays(61))).isEmpty(); // đúng 62 ngày
    }

    // ---------------------------------------------------------------- ghi vắng đột xuất (#7): thứ tự và kết quả

    @Test
    void absenceChecksScopeBeforeAnyLockThenLocksAccountsAssignmentRequestReadsTheConfigThenWritesAuditsAndNotifies() {
        givenAssignment(TODAY.minusDays(1));

        ShiftAssignmentResponse response = service.recordShiftAbsence(ASSIGNMENT, reason("  Nhân viên báo ốm  "));

        InOrder order = inOrder(branchScope, staffDirectory, definitions, assignments, requests, configs, audit,
                notifications);
        order.verify(assignments).findScopeById(ASSIGNMENT);
        order.verify(branchScope).check(BRANCH);
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
        order.verify(requests).findPendingByAssignmentIdForUpdate(ASSIGNMENT);
        order.verify(definitions).findById(DEFINITION);
        order.verify(configs).getInt(ConfigKey.WORKFORCE_ABSENCE_REPORT_MAX_DAYS);
        order.verify(assignments).saveAndFlush(any(ShiftAssignment.class));
        order.verify(audit).record(any(AuditEntry.class));
        order.verify(notifications, times(2)).enqueue(any(NotificationRequest.class));
        assertThat(response).isEqualTo(new ShiftAssignmentResponse(ASSIGNMENT, BRANCH, STAFF, "Nhân viên 12",
                DEFINITION, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), TODAY.minusDays(1),
                ShiftAssignmentStatus.ABSENT, ShiftAssignmentSource.SELF, "Nhân viên báo ốm", null));
    }

    @Test
    void absenceRecordsTheManagerAndTheInstantAndAuditsBeforeAndAfterWithTheStrippedReason() {
        ShiftAssignment row = givenAssignment(TODAY.minusDays(1));

        service.recordShiftAbsence(ASSIGNMENT, reason("  Nhân viên báo ốm  "));

        assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);
        assertThat(row.getReason()).isEqualTo("Nhân viên báo ốm");
        assertThat(row.getStatusChangedBy()).as("người thực hiện lấy từ principal").isEqualTo(MANAGER);
        assertThat(row.getStatusChangedAt()).isEqualTo(CLOCK.instant());
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("SHIFT_ASSIGNMENT_ABSENCE_RECORDED");
        assertThat(entry.entityType()).isEqualTo("shift_assignments");
        assertThat(entry.entityId()).isEqualTo(ASSIGNMENT);
        assertThat(entry.before()).as("chụp trước khi đổi").isEqualTo(new ShiftAssignmentAuditSnapshot(STAFF,
                DEFINITION, TODAY.minusDays(1).toString(), "SCHEDULED", "SELF", null));
        assertThat(entry.after()).isEqualTo(new ShiftAssignmentAuditSnapshot(STAFF, DEFINITION,
                TODAY.minusDays(1).toString(), "ABSENT", "SELF", "Nhân viên báo ốm"));
        assertThat(entry.reason()).isEqualTo("Nhân viên báo ốm");
    }

    @Test
    void absenceRejectsThePendingRequestBySystemWithTheAbsenceNoteAndTheResponseNoLongerShowsIt() {
        ShiftAssignment row = givenAssignment(TODAY.minusDays(1));
        ShiftRequest pending = pendingRequest();
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(pending));

        ShiftAssignmentResponse response = service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));

        assertThat(pending.getStatus()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(pending.getDecidedBy()).as("hệ thống tự từ chối, không có người quyết định").isNull();
        assertThat(pending.getDecisionNote()).isEqualTo(ShiftAssignmentService.PENDING_REQUEST_ABSENCE_NOTE)
                .isNotEqualTo(ShiftAssignmentService.PENDING_REQUEST_CLOSED_NOTE);
        assertThat(pending.getClosedAt()).isEqualTo(row.getStatusChangedAt()).isEqualTo(CLOCK.instant());
        assertThat(response.pendingRequestId()).as("yêu cầu vừa đóng không còn là yêu cầu chờ").isNull();
        verify(requests).saveAndFlush(pending);
    }

    @Test
    void absenceWithoutAPendingRequestWritesNoRequestRow() {
        givenAssignment(TODAY.minusDays(1));

        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));

        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
    }

    @Test
    void absenceEnqueuesInAppThenEmailWithOnlyTheVariablesOfTheAbsenceTemplatesAndNoShiftHours() {
        givenAssignment(TODAY.minusDays(1));

        service.recordShiftAbsence(ASSIGNMENT, reason("  Nhân viên báo ốm  "));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(captor.capture());
        NotificationRequest inApp = captor.getAllValues().get(0);
        NotificationRequest email = captor.getAllValues().get(1);
        assertThat(inApp.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED_APP);
        assertThat(inApp.channel()).isEqualTo(Channel.IN_APP);
        assertThat(inApp.recipientAccountId()).as("tới nhân viên của phân ca, không phải quản lý").isEqualTo(STAFF);
        assertThat(inApp.payload()).containsOnlyKeys("ten_ca", "ngay_lam", "chi_nhanh", "ly_do")
                .containsEntry("ly_do", "Nhân viên báo ốm").containsEntry("ten_ca", "Ca sáng")
                .containsEntry("chi_nhanh", "Chi nhánh Quận 1");
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_ABSENCE_RECORDED);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("an@example.com");
        assertThat(email.payload()).containsOnlyKeys("ten_ca", "ngay_lam", "chi_nhanh", "ly_do", "ten_nhan_vien")
                .containsEntry("ten_nhan_vien", "Nhân viên 12");
    }

    @Test
    void absenceForAStaffMemberWithoutAnEmailEnqueuesOnlyTheInAppNotice() {
        givenAssignment(TODAY.minusDays(1));
        when(staffDirectory.findEmail(STAFF)).thenReturn(Optional.empty());

        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void theNoticeNamesTheBranchOfTheAssignmentEvenWhenTheStaffMemberHasSinceMovedToAnotherBranch() {
        givenAssignment(TODAY.minusDays(1));
        when(staffDirectory.findStaffForUpdate(STAFF))
                .thenReturn(Optional.of(staff(STAFF, Role.RECEPTIONIST, OTHER_BRANCH, true)));

        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));

        verify(branches).findBranch(BRANCH);
        verify(branches, never()).findBranch(OTHER_BRANCH);
    }

    // ---------------------------------------------------------------- ghi vắng đột xuất (#7): biên ngày BR-NS-07

    @Test
    void absenceAcceptsTodayEvenForAShiftThatStartsLaterTodayAndExactlyTheMaximumNumberOfDaysBack() {
        // D1: chỉ kiểm theo ngày. D2: biên inclusive, đúng N ngày trước vẫn được.
        ShiftAssignment laterToday = givenAssignment(TODAY);
        when(definitions.findById(DEFINITION)).thenReturn(Optional.of(definition(DEFINITION, BRANCH, true, "Ca chiều",
                LocalTime.of(14, 0), LocalTime.of(18, 0))));
        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm từ sáng"));
        assertThat(laterToday.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);

        ShiftAssignment oldest = givenAssignment(TODAY.minusDays(ABSENCE_MAX_DAYS));
        service.recordShiftAbsence(ASSIGNMENT, reason("Báo muộn"));
        assertThat(oldest.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);
    }

    @Test
    void absenceRejectsTomorrowAndOneDayBeyondTheLimitWithBrNs07AndWritesNothing() {
        for (LocalDate day : List.of(TODAY.plusDays(1), TODAY.minusDays(ABSENCE_MAX_DAYS + 1L))) {
            ShiftAssignment row = givenAssignment(day);
            ShiftRequest pending = pendingRequest();
            when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                    .as(day.toString()).isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("BR-NS-07");

            assertThat(row.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
            assertThat(pending.getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
        }
        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
        verifyNoInteractions(audit, notifications);
    }

    @Test
    void theRuleIdAppearsExactlyOnceInEachBrNs07MessageBecauseTheExceptionAppendsItItself() {
        for (LocalDate day : List.of(TODAY.plusDays(1), TODAY.minusDays(ABSENCE_MAX_DAYS + 1L))) {
            givenAssignment(day);

            assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                    .satisfies(error -> assertThat(error.getMessage().split("BR-NS-07", -1)).as(error.getMessage())
                            .hasSize(2));
        }
    }

    @Test
    void theLimitComesFromTheConfigOnEveryCallSoANewValueAppliesFromTheNextCheck() {
        when(configs.getInt(ConfigKey.WORKFORCE_ABSENCE_REPORT_MAX_DAYS)).thenReturn(3);

        ShiftAssignment threeBack = givenAssignment(TODAY.minusDays(3));
        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));
        assertThat(threeBack.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);

        givenAssignment(TODAY.minusDays(4));
        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("3 ngày");

        when(configs.getInt(ConfigKey.WORKFORCE_ABSENCE_REPORT_MAX_DAYS)).thenReturn(30);
        ShiftAssignment longAgo = givenAssignment(TODAY.minusDays(30));
        service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));
        assertThat(longAgo.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);
    }

    @Test
    void todayIsTheVietnameseCalendarDayNotTheUtcDay() {
        // 2026-11-16T17:30Z là 00:30 ngày 17/11 giờ Việt Nam: ca ngày 17/11 là "hôm nay", ca ngày 18/11 là tương lai.
        Clock justAfterMidnight = Clock.fixed(Instant.parse("2026-11-16T17:30:00Z"), TimeConfig.BUSINESS_ZONE);
        ShiftAssignment today = givenAssignment(TODAY);
        serviceAt(justAfterMidnight).recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));
        assertThat(today.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);

        givenAssignment(TODAY.plusDays(1));
        assertThatThrownBy(() -> serviceAt(justAfterMidnight).recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void theDayChangesExactlyAtVietnameseMidnight() {
        Clock lastSecond = Clock.fixed(Instant.parse("2026-11-17T16:59:59Z"), TimeConfig.BUSINESS_ZONE); // 23:59:59 17/11
        Clock firstSecond = Clock.fixed(Instant.parse("2026-11-17T17:00:00Z"), TimeConfig.BUSINESS_ZONE); // 00:00:00 18/11

        givenAssignment(TODAY.plusDays(1));
        assertThatThrownBy(() -> serviceAt(lastSecond).recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(BusinessRuleViolationException.class);

        ShiftAssignment nextDay = givenAssignment(TODAY.plusDays(1));
        serviceAt(firstSecond).recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm"));
        assertThat(nextDay.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);
    }

    // ---------------------------------------------------------------- ghi vắng đột xuất (#7): lỗi không ghi, không audit

    @Test
    void absenceOfAnUnknownAssignmentIs404BeforeAnyLockOrWrite() {
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertCancelLockedOrWrittenNothing();
        verifyNoInteractions(configs);
    }

    @Test
    void absenceOfAnotherBranchsAssignmentIs403AndNeverLocksThatStaffAccount() {
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.of(scope(STAFF, OTHER_BRANCH)));
        org.mockito.Mockito.doThrow(new AccessDeniedScopeException("branch:" + OTHER_BRANCH, "branch:" + BRANCH))
                .when(branchScope).check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(AccessDeniedScopeException.class);

        assertCancelLockedOrWrittenNothing();
    }

    @Test
    void absenceByACallerWithoutABranchIsRefusedBeforeEvenReadingTheAssignment() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(assignments, never()).findScopeById(anyLong());
        assertCancelLockedOrWrittenNothing();
    }

    @Test
    void everyFinalStatusIs409BeforeTheDateGuardForBothAFutureAndATooOldShift() {
        for (LocalDate day : List.of(TODAY.plusDays(5), TODAY.minusDays(30))) {
            for (ShiftAssignmentStatus finalStatus : List.of(ShiftAssignmentStatus.CANCELLED,
                    ShiftAssignmentStatus.ON_LEAVE, ShiftAssignmentStatus.ABSENT)) {
                ShiftAssignment row = givenAssignment(day);
                switch (finalStatus) {
                    case CANCELLED -> row.cancel("Đã hủy", MANAGER, CLOCK.instant());
                    case ON_LEAVE -> row.markOnLeave("Nghỉ phép", MANAGER, CLOCK.instant());
                    case ABSENT -> row.markAbsent("Vắng", MANAGER, CLOCK.instant());
                    default -> throw new IllegalStateException();
                }

                assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                        .as(day + " " + finalStatus).isInstanceOf(InvalidStateTransitionException.class);
            }
        }

        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
        verifyNoInteractions(audit, notifications, configs);
    }

    @Test
    void absenceOfAnAssignmentPointingAtANonStaffAccountIsADataErrorNotA404() {
        givenAssignment(TODAY.minusDays(1));
        when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(IllegalStateException.class);

        verify(assignments, never()).saveAndFlush(any(ShiftAssignment.class));
        verifyNoInteractions(audit, notifications);
    }

    @Test
    void aFailureAfterTheAssignmentWasWrittenPropagatesWithoutBeingCaughtSoTheTransactionRollsBack() {
        givenAssignment(TODAY.minusDays(1));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down")).when(audit).record(any(AuditEntry.class));

        assertThatThrownBy(() -> service.recordShiftAbsence(ASSIGNMENT, reason("Nhân viên báo ốm")))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit down");

        verifyNoInteractions(notifications);
    }

    // ---------------------------------------------------------------- cấu hình transaction (docs/adr/0138)

    @Test
    void cancelRunsInAWriteTransactionAtReadCommittedBecauseItRereadsAfterWaitingForTheLocks() throws Exception {
        Transactional cancel = ShiftAssignmentService.class.getMethod("cancelShiftAssignment", Long.class,
                ShiftReasonRequest.class).getAnnotation(Transactional.class);

        assertThat(cancel.readOnly()).isFalse();
        assertThat(cancel.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(cancel.noRollbackFor()).as("mọi lỗi rút cả bốn hiệu ứng").isEmpty();
        assertThat(cancel.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    @Test
    void absenceRunsInAWriteTransactionAtReadCommittedWithNoRollbackExemption() throws Exception {
        Transactional absence = ShiftAssignmentService.class.getMethod("recordShiftAbsence", Long.class,
                ShiftReasonRequest.class).getAnnotation(Transactional.class);

        assertThat(absence.readOnly()).isFalse();
        assertThat(absence.isolation()).as("đọc lại sau khi chờ khóa nên phải READ COMMITTED")
                .isEqualTo(Isolation.DEFAULT);
        assertThat(absence.noRollbackFor()).as("mọi lỗi rút cả bốn hiệu ứng").isEmpty();
        assertThat(absence.rollbackFor()).isEmpty();
        assertThat(absence.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    @Test
    void myScheduleIsReadOnlyAtRepeatableRead() throws Exception {
        Transactional mine = ShiftAssignmentService.class.getMethod("listMyShiftAssignments", LocalDate.class,
                LocalDate.class).getAnnotation(Transactional.class);

        assertThat(mine.readOnly()).isTrue();
        assertThat(mine.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    // ---------------------------------------------------------------- helpers

    private ShiftAssignmentService serviceAt(Clock clock) {
        return new ShiftAssignmentService(branchScope, staffDirectory, definitions, assignments, requests, mapper,
                transitions, notifications, branches, audit, configs, clock);
    }

    /** Dựng toàn bộ dữ liệu một lần hủy hợp lệ của phân ca {@code SELF} ở ngày {@code day}, ca 08:00–12:00. */
    private ShiftAssignment givenAssignment(LocalDate day) {
        ShiftAssignment row = ShiftAssignment.schedule(BRANCH, STAFF, DEFINITION, day, ShiftAssignmentSource.SELF);
        ReflectionTestUtils.setField(row, "id", ASSIGNMENT);
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.of(scope(STAFF, BRANCH)));
        when(assignments.findByIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(row));
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.empty());
        when(requests.saveAndFlush(any(ShiftRequest.class))).thenAnswer(call -> call.getArgument(0));
        when(definitions.findById(DEFINITION)).thenReturn(Optional.of(definition(DEFINITION, BRANCH, true)));
        when(staffDirectory.findEmail(STAFF)).thenReturn(Optional.of("an@example.com"));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(new BranchSummary(BRANCH, "Chi nhánh Quận 1",
                "1 Lê Lợi", "0900000000", BranchStatus.ACTIVE, false)));
        return row;
    }

    private static ShiftReasonRequest reason(String text) {
        return new ShiftReasonRequest(text);
    }

    private static ShiftRequest pendingRequest() {
        ShiftRequest request = ShiftRequest.submit(ASSIGNMENT, STAFF, DEFINITION, ShiftRequestType.LEAVE, null,
                "Xin nghỉ ốm");
        ReflectionTestUtils.setField(request, "id", REQUEST);
        return request;
    }

    /** Lớp ẩn danh, không dùng mock (cùng lý do {@link #overlap}). */
    private static AssignmentScope scope(long staffId, long branchId) {
        return new AssignmentScope() {
            @Override
            public Long getStaffId() {
                return staffId;
            }

            @Override
            public Long getBranchId() {
                return branchId;
            }
        };
    }

    private void assertCancelLockedOrWrittenNothing() {
        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        verify(assignments, never()).findByIdForUpdate(anyLong());
        verify(requests, never()).findPendingByAssignmentIdForUpdate(anyLong());
        verify(assignments, never()).saveAndFlush(any());
        verify(requests, never()).saveAndFlush(any());
        verifyNoInteractions(audit, notifications);
    }

    private void assertNothingLockedOrWritten() {
        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        verify(definitions, never()).findByIdForShare(anyLong());
        verify(assignments, never()).findOverlapping(anyLong(), any(), any(), any());
        verify(assignments, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private static CreateShiftAssignmentRequest request(LocalDate day) {
        return new CreateShiftAssignmentRequest(STAFF, DEFINITION, day);
    }

    private static StaffSummary staff(long id, Role role, Long branchId, boolean active) {
        return new StaffSummary(id, "Nhân viên " + id, role, branchId, active, false);
    }

    private static ShiftDefinition definition(long id, long branchId, boolean active) {
        return definition(id, branchId, active, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    private static ShiftDefinition definition(long id, long branchId, boolean active, String name, LocalTime start,
            LocalTime end) {
        ShiftDefinition definition = ShiftDefinition.create(branchId, name, start, end);
        ReflectionTestUtils.setField(definition, "id", id);
        if (!active) {
            definition.deactivate();
        }
        return definition;
    }

    private static ShiftAssignment assignment(long id, long staffId, long definitionId) {
        ShiftAssignment row = ShiftAssignment.schedule(BRANCH, staffId, definitionId, WORK_DAY,
                ShiftAssignmentSource.MANAGER);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    /** Lớp ẩn danh, không dùng mock: tạo mock bên trong {@code thenReturn(...)} là stub lồng nhau (bẫy Mockito). */
    private static OverlappingShift overlap(String name, LocalTime start, LocalTime end) {
        return new OverlappingShift() {
            @Override
            public Long getAssignmentId() {
                return 1L;
            }

            @Override
            public String getShiftName() {
                return name;
            }

            @Override
            public LocalTime getStartTime() {
                return start;
            }

            @Override
            public LocalTime getEndTime() {
                return end;
            }
        };
    }

    private static PendingRequestRow pending(long assignmentId, long requestId) {
        return new PendingRequestRow() {
            @Override
            public Long getAssignmentId() {
                return assignmentId;
            }

            @Override
            public Long getRequestId() {
                return requestId;
            }
        };
    }
}
