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
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
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
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.MedicalRecordRepository;
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
 * {@link VisitCompletionService} (docs/adr/0067): role trước mọi lần đọc, 404 → 403 chi nhánh trước khi khóa, người phụ
 * trách (403) → chức vụ (400 BR-TN-05) → 409, ghi lượt → lịch hẹn → Order rồi mới đọc dịch vụ và guard BR-KB-02, nhánh
 * từ chối trước khi ghi không chạm module nào. Hành vi trên DB thật, rollback và race ở {@code VisitCompleteIT}.
 */
class VisitCompletionServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long APPOINTMENT = 501L;
    private static final long EXAM = 11L;
    private static final long VACCINE = 12L;
    private static final Instant T = Instant.parse("2026-10-22T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-22T02:15:30.123456789Z");
    private static final Instant NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final AppointmentApi appointments = mock(AppointmentApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final MedicalRecordRepository records = mock(MedicalRecordRepository.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final VisitCompletionService service = new VisitCompletionService(scope, staff, appointments, visitOrders,
            catalog, visits, records, new VisitTransitionHandler(), orders, pets, customers,
            Mappers.getMapper(VisitMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        asVet(VET);
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
        doAnswer(inv -> ids(inv).stream().map(id -> service(id, id == VACCINE ? MedicalType.VACCINE : MedicalType.EXAM))
                .toList()).when(catalog).findServices(any());
    }

    // ================================================================ guard trước khi ghi

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, records, appointments, visitOrders, catalog);
        verify(scope, never()).check(any());
    }

    @Test
    void missingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visits).findSummary(9001L);

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void otherBranchIs403BeforeLocking() {
        summary(9001L, 9L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(appointments, visitOrders, records);
    }

    @Test
    void notTheCurrentAssigneeIs403AndWritesNothing() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET + 1, null));

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        assertNothingWritten();
    }

    @Test
    void unassignedVisitIs403BeforeTheStateCheck() {
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, null, VisitStatus.WAITING));

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(AccessDeniedScopeException.class);

        assertNothingWritten();
    }

    @Test
    void wrongRoleForTheServiceGroupIs400BrTn05BeforeTheStateCheck() {
        asCaretaker(VET);
        stubLoaded(visit(9001L, ServiceGroup.MEDICAL, VET, VisitStatus.WAITING));

        assertRule(() -> service.completeVisit(9001L), "BR-TN-05");

        assertNothingWritten();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void aVisitThatIsNotInProgressIs409AndWritesNothing(VisitStatus status) {
        Visit visit = visit(9001L, ServiceGroup.MEDICAL, VET, status);
        ReflectionTestUtils.setField(visit, "appointmentId", APPOINTMENT);
        stubLoaded(visit);

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(InvalidStateTransitionException.class);

        assertNothingWritten();
        assertThat(visit.getStatus()).isEqualTo(status);
    }

    // ================================================================ thứ tự ghi và kết quả

    @Test
    void examVisitWritesVisitThenAppointmentThenOrderThenReadsServicesThenLocksTheRecord() {
        Visit visit = inProgress(9001L, ServiceGroup.MEDICAL, VET, APPOINTMENT);
        ReflectionTestUtils.setField(visit, "needsReassign", true);
        stubLoaded(visit);
        MedicalRecord record = recordWith("Viêm da");
        doReturn(Optional.of(record)).when(records).findById(9001L);
        doReturn(List.of(EXAM)).when(visitOrders).findServiceIds(9001L);

        VisitResponse response = service.completeVisit(9001L);

        InOrder order = inOrder(scope, visits, appointments, visitOrders, catalog, records);
        order.verify(scope).check(BRANCH);
        order.verify(visits).lockById(9001L);
        order.verify(visits).saveAndFlush(visit);
        order.verify(appointments).completeByVisit(APPOINTMENT);
        order.verify(visitOrders).markPendingByVisit(9001L);
        order.verify(visitOrders).findServiceIds(9001L);
        order.verify(catalog).findServices(List.of(EXAM));
        order.verify(records).flush();
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(visit.getCompletedAt()).isEqualTo(NOW_MICROS);
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(record.getLockedAt()).isEqualTo(NOW_MICROS);
        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(response.completedAt()).isEqualTo(NOW_MICROS);
        assertThat(response.needsReassign()).isFalse();
        assertThat(response.assigneeId()).isEqualTo(VET);
        assertThat(response.orderId()).isEqualTo(10_001L);
    }

    @Test
    void walkInVisitDoesNotTouchTheAppointmentModule() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.of(recordWith("Ổn"))).when(records).findById(9001L);
        doReturn(List.of(EXAM)).when(visitOrders).findServiceIds(9001L);

        service.completeVisit(9001L);

        verifyNoInteractions(appointments);
        verify(visitOrders).markPendingByVisit(9001L);
    }

    @Test
    void groomingVisitHasNoExtraConditionAndNeverReadsTheServices() {
        asCaretaker(VET);
        stubLoaded(inProgress(9001L, ServiceGroup.GROOMING, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);

        service.completeVisit(9001L);

        verify(visitOrders).markPendingByVisit(9001L);
        verify(visitOrders, never()).findServiceIds(anyLong());
        verifyNoInteractions(catalog);
        verify(records, never()).flush();
    }

    @Test
    void completionNeverCreatesAMedicalRecord() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of(VACCINE)).when(visitOrders).findServiceIds(9001L);
        doReturn(true).when(visits).hasVaccinationInVisit(12L, 9001L);

        service.completeVisit(9001L);

        verify(records, never()).save(any());
        verify(records, never()).saveAndFlush(any());
    }

    // ================================================================ BR-KB-02 (bảng tổ hợp)

    @Test
    void examWithoutAMedicalRecordIsRejected() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of(EXAM)).when(visitOrders).findServiceIds(9001L);

        assertRule(() -> service.completeVisit(9001L), "BR-KB-02");
    }

    @Test
    void examWithARecordButNoDiagnosisIsRejected() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.of(recordWith(null))).when(records).findById(9001L);
        doReturn(List.of(EXAM)).when(visitOrders).findServiceIds(9001L);

        assertRule(() -> service.completeVisit(9001L), "BR-KB-02");
        verify(records, never()).flush();
    }

    @Test
    void examAlongsideAVaccineStillNeedsTheDiagnosisNotTheDose() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.of(recordWith("Sốt"))).when(records).findById(9001L);
        doReturn(List.of(EXAM, VACCINE)).when(visitOrders).findServiceIds(9001L);

        service.completeVisit(9001L);

        verify(visits, never()).hasVaccinationInVisit(anyLong(), anyLong());
    }

    @Test
    void vaccineOnlyWithoutADoseIsRejected() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of(VACCINE)).when(visitOrders).findServiceIds(9001L);
        doReturn(false).when(visits).hasVaccinationInVisit(12L, 9001L);

        assertRule(() -> service.completeVisit(9001L), "BR-KB-02");
    }

    @Test
    void vaccineOnlyWithADoseCompletesWithoutADiagnosis() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of(VACCINE)).when(visitOrders).findServiceIds(9001L);
        doReturn(true).when(visits).hasVaccinationInVisit(12L, 9001L);

        VisitResponse response = service.completeVisit(9001L);

        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
    }

    @Test
    void medicalVisitWithoutAnyServiceLineIsRejected() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of()).when(visitOrders).findServiceIds(9001L);

        assertRule(() -> service.completeVisit(9001L), "BR-KB-02");
        verifyNoInteractions(catalog);
    }

    @Test
    void aServiceMissingFromTheCatalogIsCorruptData() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        doReturn(Optional.empty()).when(records).findById(9001L);
        doReturn(List.of(99L)).when(visitOrders).findServiceIds(9001L);
        doReturn(List.of()).when(catalog).findServices(any());

        assertThatThrownBy(() -> service.completeVisit(9001L)).isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ lỗi callee không bị nuốt

    @Test
    void appointmentFailureIsNotSwallowedAndSalesIsNeverCalled() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, APPOINTMENT));
        InvalidStateTransitionException failure =
                new InvalidStateTransitionException("lịch hẹn", "BOOKED", "COMPLETED");
        doThrow(failure).when(appointments).completeByVisit(APPOINTMENT);

        assertThatThrownBy(() -> service.completeVisit(9001L)).isSameAs(failure);

        verifyNoInteractions(visitOrders, records, catalog);
    }

    @Test
    void salesFailureIsNotSwallowed() {
        stubLoaded(inProgress(9001L, ServiceGroup.MEDICAL, VET, null));
        InvalidStateTransitionException failure = new InvalidStateTransitionException("đơn hàng", "PAID", "PENDING");
        doThrow(failure).when(visitOrders).markPendingByVisit(9001L);

        assertThatThrownBy(() -> service.completeVisit(9001L)).isSameAs(failure);

        verify(visitOrders, never()).findServiceIds(anyLong());
        verifyNoInteractions(records);
    }

    @Test
    void completeRunsInOneReadCommittedWriteTransaction() throws Exception {
        Method method = VisitCompletionService.class.getMethod("completeVisit", Long.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    private void assertNothingWritten() {
        verify(visits, never()).saveAndFlush(any());
        verifyNoInteractions(appointments, visitOrders, records, catalog);
    }

    private void asVet(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("VET").when(principal).role();
    }

    private void asCaretaker(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("CARETAKER").when(principal).role();
    }

    private void summary(long visitId, long branchId) {
        doReturn(Optional.of(new SummaryRow(visitId, branchId, 88L, 12L, ServiceGroup.MEDICAL,
                VisitStatus.IN_PROGRESS, VET))).when(visits).findSummary(visitId);
    }

    private void stubLoaded(Visit visit) {
        summary(visit.getId(), visit.getBranchId());
        doReturn(Optional.of(visit.getId())).when(visits).lockById(visit.getId());
        doReturn(Optional.of(visit)).when(visits).findById(visit.getId());
    }

    private static Visit inProgress(long id, ServiceGroup group, Long assigneeId, Long appointmentId) {
        Visit visit = visit(id, group, assigneeId, VisitStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(visit, "appointmentId", appointmentId);
        ReflectionTestUtils.setField(visit, "calledAt", T);
        return visit;
    }

    private static Visit visit(long id, ServiceGroup group, Long assigneeId, VisitStatus status) {
        Visit visit = Visit.checkIn("TN-2610-" + id, BRANCH, 88L, 12L, null, group, PriorityClass.WALK_IN, T, 70L, T);
        ReflectionTestUtils.setField(visit, "id", id);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", status);
        return visit;
    }

    private static MedicalRecord recordWith(String diagnosis) {
        MedicalRecord record = MedicalRecord.of(9001L);
        record.rewrite("Khám", diagnosis, null, null, null, VET);
        return record;
    }

    private static ServiceInfo service(long id, MedicalType type) {
        return new ServiceInfo(id, "DV-" + id, ServiceGroup.MEDICAL, type, 100_000L, true);
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
