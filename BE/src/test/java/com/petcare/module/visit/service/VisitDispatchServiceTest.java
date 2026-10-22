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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.jpa.domain.Specification;
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
import com.petcare.module.visit.dto.AssignRequest;
import com.petcare.module.visit.dto.AssignResult;
import com.petcare.module.visit.dto.MoveVisitRequest;
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
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VisitDispatchService} (docs/adr/0061): role trước mọi lần đọc, 404 → 403 phạm vi trước khi khóa, khóa tài khoản
 * trước khóa lượt, guard 400 trước 409 (00-method L70), ghi theo đúng thứ tự, nhánh lỗi và no-op (Q4) không ghi gì.
 * Hành vi trên DB thật, rollback và race ở {@code VisitDispatchIT}, {@code VisitDispatchRaceIT}.
 */
class VisitDispatchServiceTest {

    private static final long BRANCH = 3L;
    private static final long RECEPTIONIST = 70L;
    private static final long VET_A = 45L;
    private static final long VET_B = 46L;
    private static final long CARETAKER = 47L;
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-21T02:15:30.123456789Z");

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VisitAssignmentRepository assignments = mock(VisitAssignmentRepository.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final VisitDispatchService service = new VisitDispatchService(scope, staff, visitOrders, visits,
            assignments, new VisitTransitionHandler(), orders, pets, customers, Mappers.getMapper(VisitMapper.class),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(RECEPTIONIST).when(principal).accountId();
        doReturn("RECEPTIONIST").when(principal).role();
        // Responder: tên và Order theo lô, dựng từ chính id được hỏi.
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

    // ================================================================ #5 gán

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void dispatchRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_A)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, staff, visitOrders, assignments);
        verify(scope, never()).check(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void emergencyRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.markVisitEmergency(9001L)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits);
    }

    @Test
    void missingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visits).findSummary(9001L);

        assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_A)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(staff, visitOrders, assignments);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void otherBranchIs403BeforeLockingTheAssignee() {
        summary(9001L, 9L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_A)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(staff, never()).findStaffForShare(anyLong());
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void firstAssignmentLocksAccountThenVisitThenWritesInOrder() {
        Visit visit = waiting(9001L, ServiceGroup.MEDICAL, null);
        stubLoaded(visit);
        doReturn(Optional.of(vet(VET_A, false))).when(staff).findStaffForShare(VET_A);

        AssignResult result = service.assignVisit(9001L, new AssignRequest(VET_A));

        InOrder order = inOrder(scope, staff, visits, assignments, visitOrders, orders);
        order.verify(scope).check(BRANCH);
        order.verify(staff).findStaffForShare(VET_A);
        order.verify(visits).lockById(9001L);
        order.verify(visits).findById(9001L);
        order.verify(visits).saveAndFlush(visit);
        order.verify(assignments).saveAndFlush(any(VisitAssignment.class));
        order.verify(visitOrders).setAutoLineOwner(9001L, VET_A);
        order.verify(orders).findOrderIdsByVisitIds(any());
        verify(visitOrders, never()).transferLineOwnership(anyLong(), anyLong(), anyLong());

        VisitAssignment log = capturedAssignment();
        assertThat(log.getVisitId()).isEqualTo(9001L);
        assertThat(log.getFromAccountId()).isNull();
        assertThat(log.getToAccountId()).isEqualTo(VET_A);
        assertThat(log.getAssignedBy()).isEqualTo(RECEPTIONIST);
        assertThat(log.getVisitStatusAtAssign()).isEqualTo(VisitStatus.WAITING);
        assertThat(log.getReason()).isNull();
        assertThat(log.isAssigneeWasOffline()).isTrue();

        assertThat(result.assigneeOffline()).isTrue();
        assertThat(result.visit().assigneeId()).isEqualTo(VET_A);
        assertThat(result.visit().status()).isEqualTo(VisitStatus.WAITING);
        assertThat(visit.getAssigneeId()).isEqualTo(VET_A);
    }

    @Test
    void reassigningAWaitingVisitAlsoTransfersTheOldAssigneesLines() {
        Visit visit = waiting(9001L, ServiceGroup.MEDICAL, VET_A);
        stubLoaded(visit);
        doReturn(Optional.of(vet(VET_B, true))).when(staff).findStaffForShare(VET_B);

        AssignResult result = service.assignVisit(9001L, new AssignRequest(VET_B));

        InOrder order = inOrder(visitOrders);
        order.verify(visitOrders).setAutoLineOwner(9001L, VET_B);
        order.verify(visitOrders).transferLineOwnership(9001L, VET_A, VET_B);
        assertThat(capturedAssignment().getFromAccountId()).isEqualTo(VET_A);
        assertThat(result.assigneeOffline()).isFalse();
    }

    @Test
    void assigningTheCurrentAssigneeAgainWritesNothing() {
        Visit visit = waiting(9001L, ServiceGroup.MEDICAL, VET_A);
        stubLoaded(visit);
        doReturn(Optional.of(vet(VET_A, false))).when(staff).findStaffForShare(VET_A);

        AssignResult result = service.assignVisit(9001L, new AssignRequest(VET_A));

        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(assignments, visitOrders);
        assertThat(result.assigneeOffline()).isTrue();
        assertThat(result.visit().assigneeId()).isEqualTo(VET_A);
    }

    @Test
    void assigneeMustBeActiveStaffOfTheVisitsBranch() {
        Visit visit = waiting(9001L, ServiceGroup.MEDICAL, null);
        stubLoaded(visit);
        List<Optional<StaffSummary>> invalid = List.of(
                Optional.empty(),
                Optional.of(new StaffSummary(VET_A, "A", Role.VET, BRANCH, false, true)),
                Optional.of(new StaffSummary(VET_A, "A", Role.VET, 9L, true, true)),
                Optional.of(new StaffSummary(VET_A, "A", Role.SUPER_MANAGER, null, true, true)));
        for (Optional<StaffSummary> found : invalid) {
            doReturn(found).when(staff).findStaffForShare(VET_A);

            assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_A)))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                        assertThat(ex.getRuleId()).isEqualTo("BR-TN-05");
                        assertThat(ex.getMessage()).startsWith("Nhân viên không hoạt động tại chi nhánh này");
                    });
        }
        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(assignments, visitOrders);
    }

    @Test
    void roleMustMatchTheServiceGroupBothWays() {
        Visit medical = waiting(9001L, ServiceGroup.MEDICAL, null);
        Visit grooming = waiting(9002L, ServiceGroup.GROOMING, null);
        stubLoaded(medical);
        stubLoaded(grooming);
        doReturn(Optional.of(new StaffSummary(CARETAKER, "C", Role.CARETAKER, BRANCH, true, true)))
                .when(staff).findStaffForShare(CARETAKER);
        doReturn(Optional.of(vet(VET_A, true))).when(staff).findStaffForShare(VET_A);
        doReturn(Optional.of(new StaffSummary(71L, "R", Role.RECEPTIONIST, BRANCH, true, true)))
                .when(staff).findStaffForShare(71L);

        assertRule(() -> service.assignVisit(9001L, new AssignRequest(CARETAKER)), "BR-TN-05");
        assertRule(() -> service.assignVisit(9002L, new AssignRequest(VET_A)), "BR-TN-05");
        assertRule(() -> service.assignVisit(9001L, new AssignRequest(71L)), "BR-TN-05");
        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(assignments, visitOrders);
    }

    @Test
    void calledVisitIs409ButAnInvalidAssigneeIs400First() {
        Visit called = waiting(9001L, ServiceGroup.MEDICAL, VET_A);
        ReflectionTestUtils.setField(called, "status", VisitStatus.IN_PROGRESS);
        stubLoaded(called);
        doReturn(Optional.of(vet(VET_B, true))).when(staff).findStaffForShare(VET_B);
        doReturn(Optional.of(new StaffSummary(CARETAKER, "C", Role.CARETAKER, BRANCH, true, true)))
                .when(staff).findStaffForShare(CARETAKER);

        assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_B)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertRule(() -> service.assignVisit(9001L, new AssignRequest(CARETAKER)), "BR-TN-05");
        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(assignments, visitOrders);
    }

    @Test
    void finishedVisitsCannotBeAssigned() {
        for (VisitStatus status : List.of(VisitStatus.COMPLETED, VisitStatus.CANCELLED)) {
            Visit visit = waiting(9001L, ServiceGroup.MEDICAL, VET_A);
            ReflectionTestUtils.setField(visit, "status", status);
            stubLoaded(visit);
            doReturn(Optional.of(vet(VET_B, true))).when(staff).findStaffForShare(VET_B);

            assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_B)))
                    .as(status.name()).isInstanceOf(InvalidStateTransitionException.class);
        }
        verifyNoInteractions(assignments, visitOrders);
    }

    @Test
    void visitVanishingAfterThePreReadIsCorruptData() {
        summary(9001L, BRANCH);
        doReturn(Optional.of(vet(VET_A, true))).when(staff).findStaffForShare(VET_A);
        doReturn(Optional.empty()).when(visits).lockById(9001L);

        assertThatThrownBy(() -> service.assignVisit(9001L, new AssignRequest(VET_A)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(assignments, visitOrders);
    }

    // ================================================================ #6 sắp lại

    @Test
    void placingAVisitBeforeItselfIs400BeforeReadingTheTargetOrLocking() {
        summary(9001L, BRANCH);

        assertRule(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9001L)), "BR-TN-04");
        verify(visits, never()).lockWaitingIdsOfBranch(anyLong());
        verify(visits, never()).findSummary(9002L);
    }

    @Test
    void missingOrForeignTargetIsRejectedBeforeLocking() {
        summary(9001L, BRANCH);
        doReturn(Optional.empty()).when(visits).findSummary(9002L);
        assertThatThrownBy(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L)))
                .isInstanceOf(ResourceNotFoundException.class);

        summary(9003L, 9L);
        assertRule(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9003L)), "BR-TN-04");
        verify(visits, never()).lockWaitingIdsOfBranch(anyLong());
    }

    @Test
    void sourceScopeIsCheckedBeforeTheTargetIsRead() {
        summary(9001L, 9L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(visits, never()).findSummary(9002L);
    }

    @Test
    void moveLocksTheBranchWaitingSetThenReordersFlushesAndReturnsTheQueue() {
        Visit a = queued(9001L, PriorityClass.WALK_IN, T);
        Visit b = queued(9002L, PriorityClass.WALK_IN, T.plusSeconds(60));
        Visit c = queued(9003L, PriorityClass.WALK_IN, T.plusSeconds(120));
        stubWaitingSet(a, b, c);

        List<VisitResponse> queue = service.moveVisitInQueue(9003L, new MoveVisitRequest(9002L));

        InOrder order = inOrder(visits);
        order.verify(visits).lockWaitingIdsOfBranch(BRANCH);
        order.verify(visits).findById(9003L);
        order.verify(visits).flush();
        order.verify(visits).findAll(any(Specification.class));
        verify(visits, never()).lockById(anyLong());
        assertThat(c.getQueueSortKey()).isEqualTo(T.plusSeconds(60).minusNanos(1_000));
        assertThat(queue).extracting(VisitResponse::visitId).containsExactly(9001L, 9003L, 9002L);
    }

    @Test
    void moveIntoTheSamePlaceWritesNothing() {
        Visit a = queued(9001L, PriorityClass.WALK_IN, T);
        Visit b = queued(9002L, PriorityClass.WALK_IN, T.plusSeconds(60));
        stubWaitingSet(a, b);

        service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L));

        verify(visits, never()).flush();
        assertThat(a.getQueueSortKey()).isEqualTo(T);
    }

    @Test
    void crossingTheEmergencyBoundaryIs400UnderTheLockEvenWhenNotWaiting() {
        Visit normal = queued(9001L, PriorityClass.WALK_IN, T);
        Visit emergency = queued(9002L, PriorityClass.EMERGENCY, T);
        ReflectionTestUtils.setField(normal, "status", VisitStatus.IN_PROGRESS);
        stubWaitingSet(emergency);
        stubLoaded(normal);

        assertRule(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L)), "BR-TN-04");
        assertRule(() -> service.moveVisitInQueue(9002L, new MoveVisitRequest(9001L)), "BR-TN-04");
        verify(visits, never()).flush();
    }

    @Test
    void sourceOrTargetNoLongerWaitingIsLockedSeparatelyThen409() {
        Visit waiting = queued(9001L, PriorityClass.WALK_IN, T);
        Visit called = queued(9002L, PriorityClass.WALK_IN, T.plusSeconds(60));
        ReflectionTestUtils.setField(called, "status", VisitStatus.IN_PROGRESS);
        stubWaitingSet(waiting);
        stubLoaded(called);

        assertThatThrownBy(() -> service.moveVisitInQueue(9001L, new MoveVisitRequest(9002L)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.moveVisitInQueue(9002L, new MoveVisitRequest(9001L)))
                .isInstanceOf(InvalidStateTransitionException.class);

        InOrder order = inOrder(visits);
        order.verify(visits).lockById(9002L);
        order.verify(visits).findById(9002L);
        verify(visits, never()).flush();
    }

    // ================================================================ #7 cấp cứu

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "VET"})
    void markEmergencyLocksThenSetsTheClassAndTheTruncatedNow(String role) {
        doReturn(role).when(principal).role();
        Visit visit = queued(9001L, PriorityClass.WALK_IN, T);
        stubLoaded(visit);

        VisitResponse response = service.markVisitEmergency(9001L);

        InOrder order = inOrder(scope, visits);
        order.verify(scope).check(BRANCH);
        order.verify(visits).lockById(9001L);
        order.verify(visits).findById(9001L);
        order.verify(visits).saveAndFlush(visit);
        Instant micros = Instant.parse("2026-10-21T02:15:30.123456Z");
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.EMERGENCY);
        assertThat(visit.getQueueSortKey()).isEqualTo(micros);
        assertThat(response.queueSortKey()).isEqualTo(micros);
        assertThat(response.priorityClass()).isEqualTo(PriorityClass.EMERGENCY);
    }

    @Test
    void markingTwiceOrACalledVisitIs409AndWritesNothing() {
        Visit emergency = queued(9001L, PriorityClass.EMERGENCY, T);
        Visit called = queued(9002L, PriorityClass.WALK_IN, T);
        ReflectionTestUtils.setField(called, "status", VisitStatus.IN_PROGRESS);
        stubLoaded(emergency);
        stubLoaded(called);

        assertThatThrownBy(() -> service.markVisitEmergency(9001L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("hạng ưu tiên của lượt");
        assertThatThrownBy(() -> service.markVisitEmergency(9002L))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(visits, never()).saveAndFlush(any());
        assertThat(emergency.getQueueSortKey()).isEqualTo(T);
    }

    // ================================================================ transaction

    @Test
    void everyCommandRunsInItsOwnReadCommittedWriteTransaction() throws Exception {
        for (Method method : List.of(
                VisitDispatchService.class.getMethod("assignVisit", Long.class, AssignRequest.class),
                VisitDispatchService.class.getMethod("moveVisitInQueue", Long.class, MoveVisitRequest.class),
                VisitDispatchService.class.getMethod("markVisitEmergency", Long.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.DEFAULT);
            assertThat(tx.readOnly()).as(method.getName()).isFalse();
            assertThat(tx.noRollbackFor()).as(method.getName()).isEmpty();
            assertThat(tx.noRollbackForClassName()).as(method.getName()).isEmpty();
        }
    }

    @Test
    void lockMethodsOfTheRepositoryAreMandatory() throws Exception {
        for (Method method : List.of(VisitRepository.class.getMethod("lockById", Long.class),
                VisitRepository.class.getMethod("lockWaitingIdsOfBranch", Long.class))) {
            assertThat(method.getAnnotation(Transactional.class).propagation()).as(method.getName())
                    .isEqualTo(Propagation.MANDATORY);
        }
    }

    // ================================================================ helpers

    private void summary(long visitId, long branchId) {
        doReturn(Optional.of(new SummaryRow(visitId, branchId, 88L, 12L, ServiceGroup.MEDICAL, VisitStatus.WAITING,
                null))).when(visits).findSummary(visitId);
    }

    private void stubLoaded(Visit visit) {
        summary(visit.getId(), visit.getBranchId());
        doReturn(Optional.of(visit.getId())).when(visits).lockById(visit.getId());
        doReturn(Optional.of(visit)).when(visits).findById(visit.getId());
    }

    /** Các lượt WAITING bị khóa bằng câu tập; {@code findAll} của hàng đợi trả đúng chúng. */
    private void stubWaitingSet(Visit... waiting) {
        List<Visit> all = List.of(waiting);
        for (Visit visit : all) {
            stubLoaded(visit);
        }
        doReturn(all.stream().map(Visit::getId).toList()).when(visits).lockWaitingIdsOfBranch(BRANCH);
        doAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return all.stream().filter(v -> ids.contains(v.getId())).toList();
        }).when(visits).findAllById(any());
        doReturn(all).when(visits).findAll(any(Specification.class));
    }

    private VisitAssignment capturedAssignment() {
        ArgumentCaptor<VisitAssignment> captor = ArgumentCaptor.forClass(VisitAssignment.class);
        verify(assignments).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static Visit waiting(long id, ServiceGroup group, Long assigneeId) {
        Visit visit = Visit.checkIn("TN-2610-" + id, BRANCH, 88L, 12L, null, group, PriorityClass.WALK_IN, T,
                RECEPTIONIST, T);
        ReflectionTestUtils.setField(visit, "id", id);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        return visit;
    }

    private static Visit queued(long id, PriorityClass priorityClass, Instant key) {
        Visit visit = Visit.checkIn("TN-2610-" + id, BRANCH, 88L, id, null, ServiceGroup.MEDICAL, priorityClass, key,
                RECEPTIONIST, key);
        ReflectionTestUtils.setField(visit, "id", id);
        return visit;
    }

    private static StaffSummary vet(long accountId, boolean online) {
        return new StaffSummary(accountId, "BS-" + accountId, Role.VET, BRANCH, true, online);
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
