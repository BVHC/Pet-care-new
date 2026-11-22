package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.workforce.dto.CreateShiftDefinitionRequest;
import com.petcare.module.workforce.dto.ShiftDefinitionAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftDefinitionResponse;
import com.petcare.module.workforce.dto.UpdateShiftDefinitionRequest;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftDefinitionMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftDefinitionService} (workforce-v1 #1–3, docs/adr/0138): thứ tự phân quyền → khóa → guard → ghi → audit,
 * mọi nhánh lỗi không ghi và không audit, không có gì đổi thì không ghi, {@code before} chụp trước khi đổi.
 */
class ShiftDefinitionServiceTest {

    private static final long BRANCH = 5L;
    private static final long ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final ShiftDefinitionRepository definitions = mock(ShiftDefinitionRepository.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftDefinitionMapper mapper = spy(Mappers.getMapper(ShiftDefinitionMapper.class));
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);

    private ShiftDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new ShiftDefinitionService(branchScope, definitions, assignments, mapper, audit, CLOCK);
        when(branchScope.current()).thenReturn(principal);
        when(principal.role()).thenReturn("BRANCH_MANAGER");
        when(principal.branchId()).thenReturn(BRANCH);
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        when(definitions.saveAndFlush(any(ShiftDefinition.class))).thenAnswer(call -> {
            ShiftDefinition saved = call.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", ID);
            }
            return saved;
        });
    }

    // ---------------------------------------------------------------- list

    @Test
    void managerSeesInactiveShiftsUnlessAskedOtherwise() {
        when(definitions.findByBranch(BRANCH, false)).thenReturn(List.of());
        when(definitions.findByBranch(BRANCH, true)).thenReturn(List.of());

        service.listShiftDefinitions(null);
        service.listShiftDefinitions(false);
        service.listShiftDefinitions(true);

        verify(definitions, org.mockito.Mockito.times(2)).findByBranch(BRANCH, false);
        verify(definitions).findByBranch(BRANCH, true);
    }

    @Test
    void staffAlwaysGetOnlyActiveShiftsWhateverTheyAsk() {
        for (String role : List.of("RECEPTIONIST", "VET", "CARETAKER")) {
            when(principal.role()).thenReturn(role);

            service.listShiftDefinitions(false);
            service.listShiftDefinitions(null);
        }

        verify(definitions, org.mockito.Mockito.times(6)).findByBranch(BRANCH, true);
        verify(definitions, never()).findByBranch(BRANCH, false);
    }

    @Test
    void listWithoutABranchIsDeniedInsteadOfReturningAnEmptyList() {
        when(branchScope.resolve(null)).thenReturn(null);

        assertThatThrownBy(() -> service.listShiftDefinitions(null)).isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(definitions);
    }

    // ---------------------------------------------------------------- create

    @Test
    void createRunsGuardsThenWritesThenAuditsThenMaps() {
        when(definitions.existsByBranchAndName(BRANCH, "Ca sáng", null)).thenReturn(false);

        ShiftDefinitionResponse response = service.createShiftDefinition(
                new CreateShiftDefinitionRequest("  Ca sáng  ", LocalTime.of(8, 0), LocalTime.of(12, 0)));

        InOrder order = inOrder(branchScope, definitions, audit, mapper);
        order.verify(branchScope).current();
        order.verify(definitions).existsByBranchAndName(BRANCH, "Ca sáng", null);
        order.verify(definitions).saveAndFlush(any(ShiftDefinition.class));
        order.verify(mapper).toSnapshot(any(ShiftDefinition.class));
        order.verify(audit).record(any(AuditEntry.class));
        order.verify(mapper).toResponse(any(ShiftDefinition.class));
        assertThat(response).isEqualTo(
                new ShiftDefinitionResponse(ID, BRANCH, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true));
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("SHIFT_DEFINITION_CREATED");
        assertThat(entry.entityType()).isEqualTo("shift_definitions");
        assertThat(entry.entityId()).isEqualTo(ID);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isEqualTo(new ShiftDefinitionAuditSnapshot("Ca sáng", "08:00", "12:00", true));
    }

    @Test
    void createRejectsATimeOrderThatIsNotStrictlyIncreasing() {
        for (LocalTime end : List.of(LocalTime.of(8, 0), LocalTime.of(7, 59))) {
            assertThatThrownBy(() -> service.createShiftDefinition(
                    new CreateShiftDefinitionRequest("Ca", LocalTime.of(8, 0), end)))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");
        }

        verify(definitions, never()).existsByBranchAndName(any(), any(), any());
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void createRejectsADuplicateNameWithoutWriting() {
        when(definitions.existsByBranchAndName(BRANCH, "Ca sáng", null)).thenReturn(true);

        assertThatThrownBy(() -> service.createShiftDefinition(
                new CreateShiftDefinitionRequest("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");

        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void createForACallerWithoutABranchIsDenied() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.createShiftDefinition(
                new CreateShiftDefinitionRequest("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0))))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(definitions, audit);
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateAuthorisesBeforeLockingAndLocksBeforeAnyGuard() {
        ShiftDefinition definition = existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(definitions.existsByBranchAndName(BRANCH, "Ca mới", ID)).thenReturn(false);

        service.updateShiftDefinition(ID, new UpdateShiftDefinitionRequest("Ca mới", null, null, null));

        InOrder order = inOrder(definitions, branchScope, audit, mapper);
        order.verify(definitions).findBranchIdById(ID);
        order.verify(branchScope).check(BRANCH);
        order.verify(definitions).findByIdForUpdate(ID);
        order.verify(definitions).existsByBranchAndName(BRANCH, "Ca mới", ID);
        order.verify(definitions).saveAndFlush(definition);
        order.verify(audit).record(any(AuditEntry.class));
        order.verify(mapper).toResponse(definition);
    }

    @Test
    void updateCapturesBeforeTheChangeAndAfterTheSave() {
        existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(assignments.existsAnyForDefinition(BRANCH, ID)).thenReturn(false);

        service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("Ca A", LocalTime.of(7, 30), LocalTime.of(11, 30), null));

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("SHIFT_DEFINITION_UPDATED");
        assertThat(entry.before()).isEqualTo(new ShiftDefinitionAuditSnapshot("Ca sáng", "08:00", "12:00", true));
        assertThat(entry.after()).isEqualTo(new ShiftDefinitionAuditSnapshot("Ca A", "07:30", "11:30", true));
    }

    @Test
    void updateOfAMissingShiftIs404AndNothingElseRuns() {
        when(definitions.findBranchIdById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("x", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(branchScope, never()).check(anyLong());
        verify(definitions, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void updateOfAnotherBranchesShiftIsDeniedBeforeAnyLock() {
        when(definitions.findBranchIdById(ID)).thenReturn(Optional.of(99L));
        doThrow(new AccessDeniedScopeException("branch:99", "branch:5")).when(branchScope).check(99L);

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("x", null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(definitions, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void updateRejectsAResultingTimeOrderThatIsInverted() {
        ShiftDefinition definition = existing("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0));

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest(null, LocalTime.of(12, 0), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");

        assertThat(definition.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void updateRejectsADuplicateNameAndExcludesItself() {
        existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(definitions.existsByBranchAndName(BRANCH, "Ca chiều", ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("Ca chiều", null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");

        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void updateRefusesToChangeTheTimesOfAShiftThatHasAssignments() {
        ShiftDefinition definition = existing("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(assignments.existsAnyForDefinition(BRANCH, ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest(null, null, LocalTime.of(13, 0), null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");

        assertThat(definition.getEndTime()).isEqualTo(LocalTime.of(12, 0));
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void sendingTheSameTimesIsNotATimeChangeEvenWhenAssignmentsExist() {
        existing("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(assignments.existsAnyForDefinition(BRANCH, ID)).thenReturn(true);
        when(definitions.existsByBranchAndName(BRANCH, "Ca mới", ID)).thenReturn(false);

        service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("Ca mới", LocalTime.of(8, 0), LocalTime.of(12, 0), null));

        verify(assignments, never()).existsAnyForDefinition(anyLong(), anyLong());
        verify(definitions).saveAndFlush(any(ShiftDefinition.class));
    }

    @Test
    void deactivatingIsRefusedWhileScheduledAssignmentsRemainFromToday() {
        ShiftDefinition definition = existing("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(assignments.countScheduledFrom(ID, TODAY)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest(null, null, null, false)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-NS-01");

        assertThat(definition.isActive()).isTrue();
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void deactivatingAndReactivatingWriteAndAudit() {
        ShiftDefinition definition = existing("Ca", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(assignments.countScheduledFrom(ID, TODAY)).thenReturn(0L);

        service.updateShiftDefinition(ID, new UpdateShiftDefinitionRequest(null, null, null, false));
        assertThat(definition.isActive()).isFalse();
        service.updateShiftDefinition(ID, new UpdateShiftDefinitionRequest(null, null, null, true));
        assertThat(definition.isActive()).isTrue();

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(entries.capture());
        assertThat(entries.getAllValues().get(0).before())
                .isEqualTo(new ShiftDefinitionAuditSnapshot("Ca", "08:00", "12:00", true));
        assertThat(entries.getAllValues().get(0).after())
                .isEqualTo(new ShiftDefinitionAuditSnapshot("Ca", "08:00", "12:00", false));
        assertThat(entries.getAllValues().get(1).after())
                .isEqualTo(new ShiftDefinitionAuditSnapshot("Ca", "08:00", "12:00", true));
        verify(assignments, org.mockito.Mockito.times(1)).countScheduledFrom(anyLong(), any(LocalDate.class));
    }

    @Test
    void anUpdateThatChangesNothingWritesNothingAndAuditsNothing() {
        existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));

        ShiftDefinitionResponse response = service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("  Ca sáng ", LocalTime.of(8, 0), LocalTime.of(12, 0), true));

        assertThat(response.name()).isEqualTo("Ca sáng");
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        verify(definitions, never()).existsByBranchAndName(any(), any(), any());
        verify(mapper).toResponse(any(ShiftDefinition.class));
        verify(mapper, never()).toSnapshot(any(ShiftDefinition.class));
    }

    @Test
    void changingOnlyTheCaseOfTheNameIsARenameThatExcludesTheShiftItself() {
        ShiftDefinition definition = existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        when(definitions.existsByBranchAndName(BRANCH, "ca sáng", ID)).thenReturn(false);

        service.updateShiftDefinition(ID, new UpdateShiftDefinitionRequest("ca sáng", null, null, null));

        assertThat(definition.getName()).isEqualTo("ca sáng");
        verify(definitions).saveAndFlush(definition);
    }

    @Test
    void anAuditFailureAfterTheSaveIsNotSwallowed() {
        existing("Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        doThrow(new IllegalStateException("audit down")).when(audit).record(any(AuditEntry.class));
        doAnswer(call -> false).when(definitions).existsByBranchAndName(any(), any(), any());

        assertThatThrownBy(() -> service.updateShiftDefinition(ID,
                new UpdateShiftDefinitionRequest("Ca mới", null, null, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(mapper, never()).toResponse(any(ShiftDefinition.class));
    }

    // ---------------------------------------------------------------- helpers

    /** Ca có sẵn dưới khóa dòng, chi nhánh {@link #BRANCH}, id {@link #ID}. */
    private ShiftDefinition existing(String name, LocalTime start, LocalTime end) {
        ShiftDefinition definition = ShiftDefinition.create(BRANCH, name, start, end);
        ReflectionTestUtils.setField(definition, "id", ID);
        when(definitions.findBranchIdById(ID)).thenReturn(Optional.of(BRANCH));
        when(definitions.findByIdForUpdate(ID)).thenReturn(Optional.of(definition));
        return definition;
    }

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }
}
