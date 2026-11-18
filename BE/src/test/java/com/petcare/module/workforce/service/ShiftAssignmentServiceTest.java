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

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.PendingRequestRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
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
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate WORK_DAY = LocalDate.of(2026, 11, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final ShiftDefinitionRepository definitions = mock(ShiftDefinitionRepository.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftRequestRepository requests = mock(ShiftRequestRepository.class);
    private final ShiftAssignmentMapper mapper = spy(Mappers.getMapper(ShiftAssignmentMapper.class));
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);

    private ShiftAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new ShiftAssignmentService(branchScope, staffDirectory, definitions, assignments, requests, mapper,
                audit, CLOCK);
        when(branchScope.current()).thenReturn(principal);
        when(principal.branchId()).thenReturn(BRANCH);
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

    // ---------------------------------------------------------------- helpers

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
