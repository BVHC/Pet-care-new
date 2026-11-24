package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.inpatient.dto.AdmissionOrderReasonRequest;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.dto.CompleteAdmissionOrderRequest;
import com.petcare.module.inpatient.dto.CreateAdmissionOrderRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;
import com.petcare.module.inpatient.entity.DischargeOutcome;
import com.petcare.module.inpatient.fsm.AdmissionOrderTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionOrderMapper;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.module.visit.api.PrescriptionApi.PrescriptionItemRef;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AdmissionOrderService} (inpatient-v1 #5, #7, #8; docs/adr/0149 mục 26–30). Thứ tự: 404 → 403 chi nhánh → 403
 * người phụ trách / loại việc → 409 trạng thái → 400 {@code BR-LT-15}; mỗi nhánh lỗi không gọi {@code saveAndFlush}; khóa
 * ca <b>trước</b> khi ghi y lệnh (ADR-0149 mục 23). Hành vi trên DB thật, khóa chờ, rollback ở {@code AdmissionOrder*IT}.
 */
class AdmissionOrderServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long OTHER_VET = 46L;
    private static final long CARETAKER = 47L;
    private static final long ADMISSION = 51L;
    private static final long VISIT = 9001L;
    private static final long ITEM = 7001L;
    private static final long ORDER_ID = 88L;
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-22T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-23T03:15:30.123456789Z");
    private static final Instant NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS);
    private static final Instant SCHEDULED = Instant.parse("2026-11-23T05:00:00.123456789Z");

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final AdmissionOrderRepository admissionOrders = mock(AdmissionOrderRepository.class);
    private final VisitQueryApi visitQueries = mock(VisitQueryApi.class);
    private final PrescriptionApi prescriptions = mock(PrescriptionApi.class);
    private final AdmissionOrderTransitionHandler transitions = spy(new AdmissionOrderTransitionHandler());
    private final AdmissionOrderService service = new AdmissionOrderService(scope, admissions, admissionOrders,
            new AdmissionAssignees(visitQueries), prescriptions, transitions,
            Mappers.getMapper(AdmissionOrderMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn("VET").when(principal).role();
        doReturn(Optional.of(visit(VET))).when(visitQueries).findVisit(VISIT);
        doReturn(Optional.of(new PrescriptionItemRef(ITEM, false))).when(prescriptions)
                .findItemOfVisitForKeyShare(ITEM, VISIT);
    }

    // ================================================================ #5 ra y lệnh

    @Test
    void createLocksTheAdmissionBeforeAnythingIsWrittenAndSavesLast() {
        Admission admission = locked(admitted());

        AdmissionOrderResponse response = service.create(ADMISSION, medication(ITEM));

        InOrder inOrder = inOrder(admissions, scope, visitQueries, prescriptions, admissionOrders);
        inOrder.verify(admissions).findByIdForUpdate(ADMISSION);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(visitQueries).findVisit(VISIT);
        inOrder.verify(prescriptions).findItemOfVisitForKeyShare(ITEM, VISIT);
        inOrder.verify(admissionOrders).saveAndFlush(any(AdmissionOrder.class));
        assertThat(response.status()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertThat(response.admissionId()).isEqualTo(admission.getId());
        assertThat(response.prescriptionItemId()).isEqualTo(ITEM);
        assertThat(response.scheduledAt()).isEqualTo(SCHEDULED.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void createStripsTheContentAndRecordsTheCallerAsTheCreator() {
        locked(admitted());

        AdmissionOrderResponse response = service.create(ADMISSION,
                new CreateAdmissionOrderRequest(AdmissionOrderType.FEEDING, "  Cho ăn pate ", SCHEDULED, null));

        assertThat(response.content()).isEqualTo("Cho ăn pate");
        assertThat(response.prescriptionItemId()).isNull();
        verify(admissionOrders).saveAndFlush(org.mockito.ArgumentMatchers.<AdmissionOrder>argThat(
                o -> o.getCreatedBy().equals(VET) && o.getStatus() == AdmissionOrderStatus.ORDERED));
        verifyNoInteractions(prescriptions);
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionOrderType.class, names = "MEDICATION", mode = EnumSource.Mode.EXCLUDE)
    void aNonMedicationOrderWithAPrescriptionItemIsRejectedWithoutLookingItUp(AdmissionOrderType type) {
        locked(admitted());

        assertThatThrownBy(() -> service.create(ADMISSION, new CreateAdmissionOrderRequest(type, "Nội dung",
                SCHEDULED, ITEM))).isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-15)");

        verifyNoInteractions(prescriptions);
        assertNothingSaved();
    }

    @Test
    void aMedicationOrderWithoutAPrescriptionItemIsRejected() {
        locked(admitted());

        assertThatThrownBy(() -> service.create(ADMISSION, medication(null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-15)");

        verifyNoInteractions(prescriptions);
        assertNothingSaved();
    }

    @Test
    void anItemOfAnotherVisitOrAMissingOneIsRejected() {
        locked(admitted());
        doReturn(Optional.empty()).when(prescriptions).findItemOfVisitForKeyShare(ITEM, VISIT);

        assertThatThrownBy(() -> service.create(ADMISSION, medication(ITEM)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("không thuộc lượt khám")
                .hasMessageEndingWith("(BR-LT-15)");

        assertNothingSaved();
    }

    @Test
    void anExternalPurchaseItemIsRejected() {
        locked(admitted());
        doReturn(Optional.of(new PrescriptionItemRef(ITEM, true))).when(prescriptions)
                .findItemOfVisitForKeyShare(ITEM, VISIT);

        assertThatThrownBy(() -> service.create(ADMISSION, medication(ITEM)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("mua ngoài")
                .hasMessageEndingWith("(BR-LT-15)");

        assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "　", "  　"})
    void blankContentIsRejectedAfterTheStateCheckAndBeforeTheLookup(String content) {
        locked(admitted());

        assertThatThrownBy(() -> service.create(ADMISSION, new CreateAdmissionOrderRequest(
                AdmissionOrderType.MEDICATION, content, SCHEDULED, ITEM)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-15)");

        verifyNoInteractions(prescriptions);
        assertNothingSaved();
    }

    @Test
    void createOfAMissingAdmissionIs404AndReadsNothingElse() {
        doReturn(Optional.empty()).when(admissions).findByIdForUpdate(ADMISSION);

        assertThatThrownBy(() -> service.create(ADMISSION, medication(ITEM)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(visitQueries, prescriptions, admissionOrders);
    }

    @Test
    void createInAnotherBranchIs403BeforeTheAssigneeAndTheState() {
        locked(discharged());
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.create(ADMISSION, medication(ITEM)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(visitQueries, prescriptions);
        assertNothingSaved();
    }

    @Test
    void createByAVetWhoIsNotTheAssigneeIs403BeforeTheState() {
        locked(discharged());
        doReturn(Optional.of(visit(OTHER_VET))).when(visitQueries).findVisit(VISIT);

        assertThatThrownBy(() -> service.create(ADMISSION, medication(ITEM)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(prescriptions);
        assertNothingSaved();
    }

    @Test
    void createOnADischargedAdmissionIs409EvenWhenTheContentIsBlankAndTheItemMissing() {
        locked(discharged());

        assertThatThrownBy(() -> service.create(ADMISSION, new CreateAdmissionOrderRequest(
                AdmissionOrderType.MEDICATION, "  ", SCHEDULED, null)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(prescriptions);
        assertNothingSaved();
    }

    // ================================================================ #7 thực hiện

    @Test
    void completeLocksTheOrderThenReadsTheAdmissionAndSavesLast() {
        locked(ordered(AdmissionOrderType.FEEDING, null));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        AdmissionOrderResponse response = service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("  Đã cho ăn "));

        InOrder inOrder = inOrder(admissionOrders, admissions, scope, transitions);
        inOrder.verify(admissionOrders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(admissions).findById(ADMISSION);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(transitions).validateTransition(AdmissionOrderStatus.ORDERED, AdmissionOrderStatus.DONE);
        inOrder.verify(admissionOrders).saveAndFlush(any(AdmissionOrder.class));
        assertThat(response.status()).isEqualTo(AdmissionOrderStatus.DONE);
        assertThat(response.doneNote()).isEqualTo("Đã cho ăn");
        assertThat(response.doneAt()).isEqualTo(NOW_MICROS);
        verify(admissionOrders).saveAndFlush(org.mockito.ArgumentMatchers.<AdmissionOrder>argThat(
                o -> VET == o.getDoneBy()));
        verifyNoInteractions(visitQueries);
    }

    @Test
    void anyVetOfTheBranchMayCompleteEvenWhenNotTheAssignee() {
        locked(ordered(AdmissionOrderType.MEDICATION, ITEM));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);
        doReturn(OTHER_VET).when(principal).accountId();

        assertThat(service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("Đã tiêm")).status())
                .isEqualTo(AdmissionOrderStatus.DONE);

        verifyNoInteractions(visitQueries);
    }

    @Test
    void aCaretakerMayCompleteANonMedicationOrder() {
        asCaretaker();
        locked(ordered(AdmissionOrderType.MONITORING, null));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThat(service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("Đã đo")).doneAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void aCaretakerCannotCompleteAMedicationOrderAndNothingIsWritten() {
        asCaretaker();
        locked(ordered(AdmissionOrderType.MEDICATION, ITEM));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("Đã tiêm")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(transitions, never()).validateTransition(any(), any());
        assertNothingSaved();
    }

    @Test
    void aCaretakerOnAFinishedMedicationOrderGets403NotTheConflict() {
        asCaretaker();
        AdmissionOrder done = ordered(AdmissionOrderType.MEDICATION, ITEM);
        done.complete(VET, NOW, "Đã tiêm");
        locked(done);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("Lại")))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void completeAFinishedOrderIs409EvenWithABlankNote() {
        AdmissionOrder done = ordered(AdmissionOrderType.FEEDING, null);
        done.cancel("Không cần");
        locked(done);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("   ")))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "　"})
    void completeWithABlankNoteIs400AndWritesNothing(String note) {
        AdmissionOrder order = ordered(AdmissionOrderType.FEEDING, null);
        locked(order);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest(note)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-15)");

        assertThat(order.getStatus()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertNothingSaved();
    }

    @Test
    void completeOfAMissingOrderIs404AndOfAnotherBranchIs403() {
        doReturn(Optional.empty()).when(admissionOrders).findByIdForUpdate(ORDER_ID);
        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);

        locked(ordered(AdmissionOrderType.FEEDING, null));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);
        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("x")))
                .isInstanceOf(AccessDeniedScopeException.class);

        assertNothingSaved();
    }

    @Test
    void anOrderWhoseAdmissionVanishedIsCorruptData() {
        locked(ordered(AdmissionOrderType.FEEDING, null));
        doReturn(Optional.empty()).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.complete(ORDER_ID, new CompleteAdmissionOrderRequest("x")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.cancel(ORDER_ID, new AdmissionOrderReasonRequest("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ #8 hủy

    @Test
    void cancelLocksTheOrderChecksTheAssigneeThenSavesLast() {
        locked(ordered(AdmissionOrderType.FEEDING, null));
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        AdmissionOrderResponse response = service.cancel(ORDER_ID, new AdmissionOrderReasonRequest(" Thú đã ăn "));

        InOrder inOrder = inOrder(admissionOrders, admissions, scope, visitQueries, transitions);
        inOrder.verify(admissionOrders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(admissions).findById(ADMISSION);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(visitQueries).findVisit(VISIT);
        inOrder.verify(transitions).validateTransition(AdmissionOrderStatus.ORDERED, AdmissionOrderStatus.CANCELLED);
        inOrder.verify(admissionOrders).saveAndFlush(any(AdmissionOrder.class));
        assertThat(response.status()).isEqualTo(AdmissionOrderStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo("Thú đã ăn");
    }

    @Test
    void cancelByAVetWhoIsNotTheAssigneeIs403BeforeTheStateAndThePayload() {
        AdmissionOrder done = ordered(AdmissionOrderType.FEEDING, null);
        done.cancel("Cũ");
        locked(done);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);
        doReturn(Optional.of(visit(OTHER_VET))).when(visitQueries).findVisit(VISIT);

        assertThatThrownBy(() -> service.cancel(ORDER_ID, new AdmissionOrderReasonRequest("  ")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(transitions, never()).validateTransition(any(), any());
        assertNothingSaved();
    }

    @Test
    void cancelAFinishedOrderIs409EvenWithABlankReason() {
        AdmissionOrder done = ordered(AdmissionOrderType.FEEDING, null);
        done.complete(VET, NOW, "Đã làm");
        locked(done);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.cancel(ORDER_ID, new AdmissionOrderReasonRequest("")))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "　"})
    void cancelWithABlankReasonIs400AndWritesNothing(String reason) {
        AdmissionOrder order = ordered(AdmissionOrderType.FEEDING, null);
        locked(order);
        doReturn(Optional.of(admitted())).when(admissions).findById(ADMISSION);

        assertThatThrownBy(() -> service.cancel(ORDER_ID, new AdmissionOrderReasonRequest(reason)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-15)");

        assertThat(order.getStatus()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertNothingSaved();
    }

    @Test
    void cancelOfAMissingOrderIs404() {
        doReturn(Optional.empty()).when(admissionOrders).findByIdForUpdate(ORDER_ID);

        assertThatThrownBy(() -> service.cancel(ORDER_ID, new AdmissionOrderReasonRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertNothingSaved();
    }

    // ================================================================ transaction

    @Test
    void everyWriteRunsInOneReadCommittedTransactionWithoutNoRollbackFor() throws Exception {
        for (Method method : new Method[] {
                AdmissionOrderService.class.getMethod("create", Long.class, CreateAdmissionOrderRequest.class),
                AdmissionOrderService.class.getMethod("complete", Long.class, CompleteAdmissionOrderRequest.class),
                AdmissionOrderService.class.getMethod("cancel", Long.class, AdmissionOrderReasonRequest.class)}) {
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

    private static CreateAdmissionOrderRequest medication(Long itemId) {
        return new CreateAdmissionOrderRequest(AdmissionOrderType.MEDICATION, "Tiêm kháng sinh", SCHEDULED, itemId);
    }

    private void asCaretaker() {
        doReturn(CARETAKER).when(principal).accountId();
        doReturn("CARETAKER").when(principal).role();
    }

    private Admission locked(Admission admission) {
        doReturn(Optional.of(admission)).when(admissions).findByIdForUpdate(ADMISSION);
        return admission;
    }

    private AdmissionOrder locked(AdmissionOrder order) {
        doReturn(Optional.of(order)).when(admissionOrders).findByIdForUpdate(ORDER_ID);
        return order;
    }

    private static Admission admitted() {
        Admission admission = Admission.admit("NT-2611-00007", VISIT, 12L, BRANCH, null, 17L, 41L, 250_000L, VET,
                ADMITTED_AT);
        ReflectionTestUtils.setField(admission, "id", ADMISSION);
        return admission;
    }

    private static Admission discharged() {
        Admission admission = admitted();
        admission.discharge(DischargeOutcome.HOME, "Ổn định", VET, NOW, 777L, 0);
        return admission;
    }

    private static AdmissionOrder ordered(AdmissionOrderType type, Long itemId) {
        AdmissionOrder order = AdmissionOrder.order(admitted(), type, "Nội dung", SCHEDULED, itemId, VET);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    private static VisitSummary visit(long assignee) {
        return new VisitSummary(VISIT, BRANCH, 88L, 12L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, assignee);
    }

    private void assertNothingSaved() {
        verify(admissionOrders, never()).saveAndFlush(any());
    }
}
