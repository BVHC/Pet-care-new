package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.dto.ReassignRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitAssignment;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitAssignmentRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VisitReassignService} (docs/adr/0063): role trước mọi lần đọc; 404 → 403 phạm vi (BRANCH_MANAGER chi nhánh,
 * SUPER_MANAGER khi chi nhánh không còn quản lý hoạt động) trước khi khóa; khóa tài khoản người cũ / người mới theo id
 * tăng dần trước khóa lượt; 409 trạng thái → 409 gán lại song song → 400 BR-TN-08 → 400 BR-TN-05 (D4); ghi lượt → lịch
 * sử → Order → audit; nhánh lỗi không ghi gì. Hành vi trên DB thật, rollback và race ở {@code VisitReassignIT}.
 */
class VisitReassignServiceTest {

    private static final long BRANCH = 3L;
    private static final long MANAGER = 30L;
    private static final long SUPER = 2L;
    private static final long VET_A = 45L;
    private static final long VET_B = 46L;
    private static final long VET_C = 47L;
    private static final long VISIT = 9001L;
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");
    private static final String REASON = "BS. Lan bị khóa giữa ca";

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitAssignmentRepository assignments = mock(VisitAssignmentRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final VisitReassignService service = new VisitReassignService(scope, staff, visitOrders, visits,
            assignments, new VisitTransitionHandler(), audit, orders, pets, customers,
            Mappers.getMapper(VisitMapper.class));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        asManager();
        doAnswer(inv -> ids(inv).stream().collect(Collectors.toMap(Function.identity(), id -> id + 1000)))
                .when(orders).findOrderIdsByVisitIds(any());
        doAnswer(inv -> ids(inv).stream()
                .map(id -> new PetSummary(id, 88L, "Thú-" + id, Species.DOG, null, null, null)).toList())
                .when(pets).findPets(any());
        doAnswer(inv -> ids(inv).stream()
                .map(id -> new CustomerContact(id, "Khách-" + id, "0900000000", "k@x.vn", null, false)).toList())
                .when(customers).findContacts(any());
        doAnswer(inv -> ids(inv).stream()
                .map(id -> new StaffSummary(id, "NV-" + id, Role.VET, BRANCH, true, true)).toList())
                .when(staff).findStaffByIds(any());
    }

    // ================================================================ role, 404, phạm vi

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "VET", "CARETAKER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, staff, assignments, visitOrders, audit);
        verify(scope, never()).check(any());
    }

    @Test
    void missingVisitIs404BeforeScopeAndLocks() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(staff);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void managerOfAnotherBranchIs403BeforeAnyLock() {
        summary(VET_A);
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(staff);
        verify(visits, never()).lockById(anyLong());
        assertNothingWritten();
    }

    @Test
    void superManagerIs403WhileTheBranchStillHasAnActiveManager() {
        asSuperManager();
        summary(VET_A);
        doReturn(List.of(MANAGER)).when(staff).findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER);

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(staff, never()).findStaffForShare(anyLong());
        verify(visits, never()).lockById(anyLong());
        assertNothingWritten();
    }

    @Test
    void superManagerReassignsWhenTheBranchHasNoActiveManagerAndSkipsTheBranchScope() {
        asSuperManager();
        doReturn(List.of()).when(staff).findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER);
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        VisitResponse response = service.reassignCalledVisit(VISIT, request(VET_B));

        assertThat(response.assigneeId()).isEqualTo(VET_B);
        verify(scope, never()).check(any());
        ArgumentCaptor<VisitAssignment> row = ArgumentCaptor.forClass(VisitAssignment.class);
        verify(assignments).saveAndFlush(row.capture());
        assertThat(row.getValue().getAssignedBy()).isEqualTo(SUPER);
    }

    // ================================================================ đường đúng

    @Test
    void reassignLocksAccountsByAscendingIdThenTheVisitThenWritesInOrder() {
        Visit visit = stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        VisitResponse response = service.reassignCalledVisit(VISIT, request(VET_B));

        InOrder order = inOrder(visits, scope, staff, assignments, visitOrders, audit);
        order.verify(visits).findSummary(VISIT);
        order.verify(scope).check(BRANCH);
        order.verify(staff).findStaffForShare(VET_A);
        order.verify(staff).findStaffForShare(VET_B);
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(visits).saveAndFlush(visit);
        order.verify(assignments).saveAndFlush(any(VisitAssignment.class));
        order.verify(visitOrders).transferLineOwnership(VISIT, VET_A, VET_B);
        order.verify(audit).record(any(AuditEntry.class));
        verify(visitOrders, never()).setAutoLineOwner(anyLong(), anyLong());

        assertThat(visit.getAssigneeId()).isEqualTo(VET_B);
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(response.assigneeId()).isEqualTo(VET_B);
        assertThat(response.needsReassign()).isFalse();
    }

    @Test
    void historyRowAndAuditCarryBothAssigneesTheFlagAndTheReason() {
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        lockable(VET_B, new StaffSummary(VET_B, "NV-46", Role.VET, BRANCH, true, false));

        service.reassignCalledVisit(VISIT, request(VET_B));

        ArgumentCaptor<VisitAssignment> row = ArgumentCaptor.forClass(VisitAssignment.class);
        verify(assignments).saveAndFlush(row.capture());
        VisitAssignment history = row.getValue();
        assertThat(history.getVisitId()).isEqualTo(VISIT);
        assertThat(history.getFromAccountId()).isEqualTo(VET_A);
        assertThat(history.getToAccountId()).isEqualTo(VET_B);
        assertThat(history.getAssignedBy()).isEqualTo(MANAGER);
        assertThat(history.getVisitStatusAtAssign()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(history.getReason()).isEqualTo(REASON);
        assertThat(history.isAssigneeWasOffline()).isTrue();

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        AuditEntry recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo("VISIT_REASSIGNED");
        assertThat(recorded.entityType()).isEqualTo("visits");
        assertThat(recorded.entityId()).isEqualTo(VISIT);
        assertThat(recorded.before()).isEqualTo(Map.of("assigneeId", VET_A, "needsReassign", true));
        assertThat(recorded.after()).isEqualTo(Map.of("assigneeId", VET_B, "needsReassign", false));
        assertThat(recorded.reason()).isEqualTo(REASON);
    }

    @Test
    void newAssigneeWithTheLowerIdIsLockedFirst() {
        stubCalled(VET_B, true);
        lockable(VET_B, staffOf(VET_B, Role.VET, false));
        lockable(VET_A, staffOf(VET_A, Role.VET, true));

        service.reassignCalledVisit(VISIT, request(VET_A));

        InOrder order = inOrder(staff, visits);
        order.verify(staff).findStaffForShare(VET_A);
        order.verify(staff).findStaffForShare(VET_B);
        order.verify(visits).lockById(VISIT);
    }

    @Test
    void assigneeWhoseRoleNoLongerMatchesTheGroupCanBeReplacedEvenWhileActive() {
        stubCalled(VET_A, false);
        lockable(VET_A, staffOf(VET_A, Role.CARETAKER, true));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        service.reassignCalledVisit(VISIT, request(VET_B));

        verify(visitOrders).transferLineOwnership(VISIT, VET_A, VET_B);
    }

    @Test
    void assigneeTransferredToAnotherBranchOrNoLongerStaffCanBeReplaced() {
        stubCalled(VET_A, false);
        lockable(VET_A, new StaffSummary(VET_A, "NV-45", Role.VET, 9L, true, true));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        service.reassignCalledVisit(VISIT, request(VET_B));

        doReturn(Optional.empty()).when(staff).findStaffForShare(VET_B);
        stubCalled(VET_B, false);
        lockable(VET_C, staffOf(VET_C, Role.VET, true));

        service.reassignCalledVisit(VISIT, request(VET_C));

        verify(visitOrders).transferLineOwnership(VISIT, VET_B, VET_C);
    }

    // ================================================================ 409 trước 400 (D4)

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void visitThatIsNotInProgressIs409EvenWhenTheAssigneeIsStillEligible(VisitStatus status) {
        Visit visit = stubCalled(VET_A, false);
        ReflectionTestUtils.setField(visit, "status", status);
        lockable(VET_A, staffOf(VET_A, Role.VET, true));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertNothingWritten();
    }

    @Test
    void unassignedWaitingVisitLocksOnlyTheNewAssigneeAndIs409() {
        Visit visit = stubCalled(null, false);
        ReflectionTestUtils.setField(visit, "status", VisitStatus.WAITING);
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(staff, times(1)).findStaffForShare(anyLong());
        assertNothingWritten();
    }

    @Test
    void assigneeChangedBetweenTheProjectionAndTheLockIs409Conflict() {
        Visit visit = stubCalled(VET_A, true);
        ReflectionTestUtils.setField(visit, "assigneeId", VET_C);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B)))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertNothingWritten();
    }

    // ================================================================ 400

    @Test
    void assigneeStillEligibleIsBrTn08EvenWithAStaleFlag() {
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, true));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));

        assertRule(() -> service.reassignCalledVisit(VISIT, request(VET_B)), "BR-TN-08");

        assertNothingWritten();
    }

    @Test
    void brTn08ComesBeforeAnInvalidNewAssignee() {
        stubCalled(VET_A, false);
        lockable(VET_A, staffOf(VET_A, Role.VET, true));
        lockable(VET_B, staffOf(VET_B, Role.CARETAKER, true));

        assertRule(() -> service.reassignCalledVisit(VISIT, request(VET_B)), "BR-TN-08");
    }

    @ParameterizedTest
    @ValueSource(strings = {"locked", "otherBranch", "wrongRole", "notStaff"})
    void newAssigneeThatCannotTakeTheVisitIsBrTn05(String why) {
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        Optional<StaffSummary> candidate = switch (why) {
            case "locked" -> Optional.of(staffOf(VET_B, Role.VET, false));
            case "otherBranch" -> Optional.of(new StaffSummary(VET_B, "NV-46", Role.VET, 9L, true, true));
            case "wrongRole" -> Optional.of(staffOf(VET_B, Role.CARETAKER, true));
            default -> Optional.empty();
        };
        doReturn(candidate).when(staff).findStaffForShare(VET_B);

        assertRule(() -> service.reassignCalledVisit(VISIT, request(VET_B)), "BR-TN-05");

        assertNothingWritten();
    }

    @Test
    void reassigningToTheLockedAssigneeHimselfLocksOnceAndIsBrTn05() {
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));

        assertRule(() -> service.reassignCalledVisit(VISIT, request(VET_A)), "BR-TN-05");

        verify(staff, times(1)).findStaffForShare(VET_A);
        assertNothingWritten();
    }

    // ================================================================ callee, transaction

    @Test
    void calleeFailureIsNotSwallowed() {
        stubCalled(VET_A, true);
        lockable(VET_A, staffOf(VET_A, Role.VET, false));
        lockable(VET_B, staffOf(VET_B, Role.VET, true));
        InvalidStateTransitionException failure = new InvalidStateTransitionException("đơn hàng", "PENDING", "OPEN");
        doThrow(failure).when(visitOrders).transferLineOwnership(VISIT, VET_A, VET_B);

        assertThatThrownBy(() -> service.reassignCalledVisit(VISIT, request(VET_B))).isSameAs(failure);

        verify(audit, never()).record(any());
    }

    @Test
    void reassignRunsInItsOwnReadCommittedWriteTransaction() throws Exception {
        Method method = VisitReassignService.class.getMethod("reassignCalledVisit", Long.class,
                ReassignRequest.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    private void asManager() {
        doReturn(MANAGER).when(principal).accountId();
        doReturn("BRANCH_MANAGER").when(principal).role();
    }

    private void asSuperManager() {
        doReturn(SUPER).when(principal).accountId();
        doReturn("SUPER_MANAGER").when(principal).role();
    }

    private void summary(Long assigneeId) {
        doReturn(Optional.of(new SummaryRow(VISIT, BRANCH, 88L, 12L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS,
                assigneeId))).when(visits).findSummary(VISIT);
    }

    /** Lượt {@code IN_PROGRESS} Khám/Tiêm của {@code assigneeId}; projection và entity cùng người phụ trách. */
    private Visit stubCalled(Long assigneeId, boolean flagged) {
        summary(assigneeId);
        Visit visit = Visit.checkIn("TN-2610-" + VISIT, BRANCH, 88L, 12L, null, ServiceGroup.MEDICAL,
                PriorityClass.WALK_IN, T, 70L, T);
        ReflectionTestUtils.setField(visit, "id", VISIT);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", VisitStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(visit, "calledAt", T);
        ReflectionTestUtils.setField(visit, "needsReassign", flagged);
        doReturn(Optional.of(VISIT)).when(visits).lockById(VISIT);
        doReturn(Optional.of(visit)).when(visits).findById(VISIT);
        return visit;
    }

    private void lockable(long accountId, StaffSummary summary) {
        doReturn(Optional.of(summary)).when(staff).findStaffForShare(accountId);
    }

    private void assertNothingWritten() {
        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(assignments, visitOrders, audit);
    }

    private static ReassignRequest request(long assigneeId) {
        return new ReassignRequest(assigneeId, REASON);
    }

    private static StaffSummary staffOf(long accountId, Role role, boolean active) {
        return new StaffSummary(accountId, "NV-" + accountId, role, BRANCH, active, true);
    }

    private static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String ruleId) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                ex -> assertThat(ex.getRuleId()).isEqualTo(ruleId));
    }

    @SuppressWarnings("unchecked")
    private static Collection<Long> ids(org.mockito.invocation.InvocationOnMock invocation) {
        return (Collection<Long>) invocation.getArgument(0);
    }
}
