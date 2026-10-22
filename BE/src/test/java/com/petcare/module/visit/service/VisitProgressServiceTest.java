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
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentApi;
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
import com.petcare.module.visit.dto.CancelVisitRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
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
 * {@link VisitProgressService} (docs/adr/0062): role trước mọi lần đọc, 404 → 403 phạm vi trước khi khóa, khóa tài khoản
 * người gọi trước khóa lượt (#8), không phải chủ lượt (403) → người gọi không hợp lệ (400) → 409 (00-method L70), hủy
 * ghi lượt rồi mới gọi appointment → sales, nhánh lỗi không ghi gì. Hành vi trên DB thật, rollback và race ở
 * {@code VisitProgressIT}.
 */
class VisitProgressServiceTest {

    private static final long BRANCH = 3L;
    private static final long RECEPTIONIST = 70L;
    private static final long VET_A = 45L;
    private static final long VET_B = 46L;
    private static final long APPOINTMENT = 501L;
    private static final Instant T = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-21T02:15:30.123456789Z");
    private static final Instant NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final AppointmentApi appointments = mock(AppointmentApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final VisitProgressService service = new VisitProgressService(scope, staff, appointments, visitOrders,
            visits, new VisitTransitionHandler(), orders, pets, customers, Mappers.getMapper(VisitMapper.class),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        asVet(VET_A);
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

    // ================================================================ #8 gọi lượt

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void callRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, staff, appointments, visitOrders);
        verify(scope, never()).check(any());
    }

    @Test
    void callOfMissingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visits).findSummary(9001L);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verify(staff, never()).findStaffForShare(anyLong());
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void callOfOtherBranchIs403BeforeLockingTheCaller() {
        summary(9001L, 9L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        verify(staff, never()).findStaffForShare(anyLong());
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void callLocksTheCallerThenTheVisitThenWrites() {
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, VET_A, VisitStatus.WAITING, null);
        stubLoaded(visit);
        doReturn(Optional.of(staffOf(VET_A, Role.VET, true))).when(staff).findStaffForShare(VET_A);

        VisitResponse response = service.callVisit(9001L);

        InOrder order = inOrder(scope, staff, visits, orders);
        order.verify(scope).check(BRANCH);
        order.verify(staff).findStaffForShare(VET_A);
        order.verify(visits).lockById(9001L);
        order.verify(visits).findById(9001L);
        order.verify(visits).saveAndFlush(visit);
        order.verify(orders).findOrderIdsByVisitIds(any());
        verifyNoInteractions(appointments, visitOrders);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getCalledAt()).isEqualTo(NOW_MICROS);
        assertThat(visit.getAssigneeId()).isEqualTo(VET_A);
        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(response.calledAt()).isEqualTo(NOW_MICROS);
        assertThat(response.cancelledAt()).isNull();
    }

    @Test
    void caretakerCallsAGroomingVisit() {
        asCaretaker(47L);
        Visit visit = visit(9001L, ServiceGroup.GROOMING, 47L, VisitStatus.WAITING, null);
        stubLoaded(visit);
        doReturn(Optional.of(staffOf(47L, Role.CARETAKER, true))).when(staff).findStaffForShare(47L);

        service.callVisit(9001L);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
    }

    @Test
    void visitAssignedToSomeoneElseIs403AndWritesNothing() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, VET_B, VisitStatus.WAITING, null));
        doReturn(Optional.of(staffOf(VET_A, Role.VET, true))).when(staff).findStaffForShare(VET_A);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void unassignedVisitIs403() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, null, VisitStatus.WAITING, null));
        doReturn(Optional.of(staffOf(VET_A, Role.VET, true))).when(staff).findStaffForShare(VET_A);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        verify(visits, never()).saveAndFlush(any());
    }

    /** Không phải chủ lượt (403) đứng trước 409: lượt đã hủy, chưa gán. */
    @Test
    void notTheAssigneeComesBeforeTheStateCheck() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, null, VisitStatus.CANCELLED, null));
        doReturn(Optional.of(staffOf(VET_A, Role.VET, true))).when(staff).findStaffForShare(VET_A);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void callerNoLongerActiveIs400() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, VET_A, VisitStatus.WAITING, null));
        doReturn(Optional.of(staffOf(VET_A, Role.VET, false))).when(staff).findStaffForShare(VET_A);

        assertRule(() -> service.callVisit(9001L), "BR-TN-05");
        verify(visits, never()).saveAndFlush(any());
    }

    @Test
    void callerWithoutStaffRowIs400() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, VET_A, VisitStatus.WAITING, null));
        doReturn(Optional.empty()).when(staff).findStaffForShare(VET_A);

        assertRule(() -> service.callVisit(9001L), "BR-TN-05");
    }

    /** Đổi chức vụ sau khi được gán (docs/adr/0031 Q4): 400 BR-TN-05, đứng trước 409. */
    @Test
    void callerWhoseRoleChangedIs400EvenWhenTheVisitIsNoLongerWaiting() {
        asCaretaker(VET_A);
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, VET_A, VisitStatus.IN_PROGRESS, T));
        doReturn(Optional.of(staffOf(VET_A, Role.CARETAKER, true))).when(staff).findStaffForShare(VET_A);

        assertRule(() -> service.callVisit(9001L), "BR-TN-05");
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void callOfAVisitThatIsNoLongerWaitingIs409(VisitStatus status) {
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, VET_A, status, T);
        stubLoaded(visit);
        doReturn(Optional.of(staffOf(VET_A, Role.VET, true))).when(staff).findStaffForShare(VET_A);

        assertThatThrownBy(() -> service.callVisit(9001L)).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(visits, never()).saveAndFlush(any());
        assertThat(visit.getCalledAt()).isEqualTo(T);
    }

    // ================================================================ #11 hủy lượt

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void cancelRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.cancelVisit(9001L, null)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, appointments, visitOrders);
    }

    @Test
    void cancelOfMissingVisitIs404BeforeAnyLock() {
        asReceptionist();
        doReturn(Optional.empty()).when(visits).findSummary(9001L);

        assertThatThrownBy(() -> service.cancelVisit(9001L, null)).isInstanceOf(ResourceNotFoundException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(appointments, visitOrders);
    }

    @Test
    void cancelOfOtherBranchIs403BeforeAnyLock() {
        asReceptionist();
        summary(9001L, 9L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.cancelVisit(9001L, null)).isInstanceOf(AccessDeniedScopeException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(appointments, visitOrders);
    }

    @Test
    void cancelOfWalkInWritesTheVisitThenCancelsTheOrderOnly() {
        asReceptionist();
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, VET_A, VisitStatus.WAITING, null);
        stubLoaded(visit);

        VisitResponse response = service.cancelVisit(9001L, new CancelVisitRequest("  Khách bỏ về  "));

        InOrder order = inOrder(scope, visits, visitOrders, orders);
        order.verify(scope).check(BRANCH);
        order.verify(visits).lockById(9001L);
        order.verify(visits).findById(9001L);
        order.verify(visits).saveAndFlush(visit);
        order.verify(visitOrders).cancelByVisit(9001L, RECEPTIONIST);
        order.verify(orders).findOrderIdsByVisitIds(any());
        verifyNoInteractions(appointments);
        verify(staff, never()).findStaffForShare(anyLong());

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(visit.getCancelledAt()).isEqualTo(NOW_MICROS);
        assertThat(visit.getCancelReason()).isEqualTo("Khách bỏ về");
        assertThat(visit.getAssigneeId()).isEqualTo(VET_A);
        assertThat(response.status()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo("Khách bỏ về");
    }

    @Test
    void cancelWithAppointmentCancelsItAtTheSameInstantBeforeTheOrder() {
        asReceptionist();
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, null, VisitStatus.WAITING, null);
        ReflectionTestUtils.setField(visit, "appointmentId", APPOINTMENT);
        stubLoaded(visit);

        service.cancelVisit(9001L, null);

        InOrder order = inOrder(visits, appointments, visitOrders);
        order.verify(visits).saveAndFlush(visit);
        order.verify(appointments).cancelByVisit(APPOINTMENT, NOW_MICROS);
        order.verify(visitOrders).cancelByVisit(9001L, RECEPTIONIST);
        assertThat(visit.getCancelledAt()).isEqualTo(NOW_MICROS);
        assertThat(visit.getCancelReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void cancelOfAVisitThatIsNoLongerWaitingIs409AndTouchesNoOtherModule(VisitStatus status) {
        asReceptionist();
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, VET_A, status, T);
        ReflectionTestUtils.setField(visit, "appointmentId", APPOINTMENT);
        stubLoaded(visit);

        assertThatThrownBy(() -> service.cancelVisit(9001L, new CancelVisitRequest("x")))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(appointments, visitOrders);
        assertThat(visit.getCancelReason()).isNull();
    }

    /** Lỗi của callee {@code MANDATORY} đi thẳng ra ngoài, không bị bắt (transaction đã rollback-only). */
    @Test
    void calleeFailureIsNotSwallowed() {
        asReceptionist();
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, null, VisitStatus.WAITING, null));
        InvalidStateTransitionException failure = new InvalidStateTransitionException("đơn hàng", "PENDING", "OPEN");
        doThrow(failure).when(visitOrders).cancelByVisit(9001L, RECEPTIONIST);

        assertThatThrownBy(() -> service.cancelVisit(9001L, null)).isSameAs(failure);

        verify(orders, never()).findOrderIdsByVisitIds(any());
    }

    @Test
    void reasonIsStrippedAndBlankBecomesNull() {
        assertThat(VisitProgressService.normalizeReason(null)).isNull();
        assertThat(VisitProgressService.normalizeReason(new CancelVisitRequest(null))).isNull();
        assertThat(VisitProgressService.normalizeReason(new CancelVisitRequest(""))).isNull();
        assertThat(VisitProgressService.normalizeReason(new CancelVisitRequest(" \t "))).isNull();
        assertThat(VisitProgressService.normalizeReason(new CancelVisitRequest("　"))).isNull();
        assertThat(VisitProgressService.normalizeReason(new CancelVisitRequest("　 Bỏ về \n"))).isEqualTo("Bỏ về");
    }

    // ================================================================ transaction

    @Test
    void everyCommandRunsInItsOwnReadCommittedWriteTransaction() throws Exception {
        for (Method method : List.of(
                VisitProgressService.class.getMethod("callVisit", Long.class),
                VisitProgressService.class.getMethod("cancelVisit", Long.class, CancelVisitRequest.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.DEFAULT);
            assertThat(tx.readOnly()).as(method.getName()).isFalse();
            assertThat(tx.noRollbackFor()).as(method.getName()).isEmpty();
            assertThat(tx.noRollbackForClassName()).as(method.getName()).isEmpty();
        }
    }

    // ================================================================ helpers

    private void asVet(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("VET").when(principal).role();
    }

    private void asCaretaker(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("CARETAKER").when(principal).role();
    }

    private void asReceptionist() {
        doReturn(RECEPTIONIST).when(principal).accountId();
        doReturn("RECEPTIONIST").when(principal).role();
    }

    private void summary(long visitId, long branchId) {
        doReturn(Optional.of(new SummaryRow(visitId, branchId, 88L, 12L, ServiceGroup.MEDICAL, VisitStatus.WAITING,
                VET_A))).when(visits).findSummary(visitId);
    }

    private void stubLoaded(Visit visit) {
        summary(visit.getId(), visit.getBranchId());
        doReturn(Optional.of(visit.getId())).when(visits).lockById(visit.getId());
        doReturn(Optional.of(visit)).when(visits).findById(visit.getId());
    }

    private static Visit visit(long id, ServiceGroup group, Long assigneeId, VisitStatus status, Instant calledAt) {
        Visit visit = Visit.checkIn("TN-2610-" + id, BRANCH, 88L, 12L, null, group, PriorityClass.WALK_IN, T,
                RECEPTIONIST, T);
        ReflectionTestUtils.setField(visit, "id", id);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", status);
        ReflectionTestUtils.setField(visit, "calledAt", calledAt);
        return visit;
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
