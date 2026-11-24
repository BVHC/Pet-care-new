package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.DischargeRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.entity.DischargeOutcome;
import com.petcare.module.inpatient.fsm.AdmissionTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
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
 * {@link AdmissionDischargeService} (inpatient-v1 #4, docs/adr/0149 mục 22–25): tóm tắt rỗng bị chặn trước khi chạm DB;
 * 404 → 403 chi nhánh → 403 người phụ trách → 409 trạng thái → 400 còn y lệnh, mỗi nhánh lỗi không thêm dòng Order, không
 * giải phóng chuồng, không lưu ca. Số lượng dòng Order là {@code billedDays} và đơn giá là giá đã chốt của ca, không phải
 * giá hiện hành của danh mục. Hành vi trên DB thật, rollback và race ở {@code AdmissionDischarge*IT}.
 */
class AdmissionDischargeServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long OTHER_VET = 46L;
    private static final long ADMISSION = 51L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    private static final long SERVICE = 41L;
    private static final long KENNEL = 17L;
    private static final long BOOKING = 301L;
    private static final long LOCKED_PRICE = 250_000L;
    private static final long TODAY_PRICE = 999_000L;
    private static final long LINE = 777L;
    /** 09:00 ngày 22/11 giờ Việt Nam. */
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-22T02:00:00Z");
    /** 10:15 ngày 23/11 giờ Việt Nam: ngày thứ hai của ca. */
    private static final Instant NOW = Instant.parse("2026-11-23T03:15:30.123456789Z");
    private static final Instant NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final AdmissionOrderRepository admissionOrders = mock(AdmissionOrderRepository.class);
    private final VisitQueryApi visitQueries = mock(VisitQueryApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final KennelApi kennels = mock(KennelApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final AdmissionTransitionHandler transitions = spy(new AdmissionTransitionHandler());
    private final AdmissionDischargeService service = new AdmissionDischargeService(scope, admissions, admissionOrders,
            new AdmissionAssignees(visitQueries), visitOrders, kennels, pets, catalog, transitions,
            Mappers.getMapper(AdmissionMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn(Optional.of(visit(VET))).when(visitQueries).findVisit(VISIT);
        doReturn(0L).when(admissionOrders).countOrderedByAdmissionId(ADMISSION);
        doReturn(new LineRef(5L, LINE)).when(visitOrders).addServiceLine(anyLong(), anyLong(), anyInt(), anyLong(),
                anyLong());
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mochi", Species.DOG, null, null, null))).when(pets).findPet(PET);
        doReturn(Optional.of(new ServiceInfo(SERVICE, "Nội trú điều trị", ServiceGroup.MEDICAL, MedicalType.INPATIENT,
                TODAY_PRICE, true))).when(catalog).findService(SERVICE);
    }

    // ================================================================ chưa chạm DB

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "　", "  　"})
    void blankSummaryIsRejectedBeforeAnyRead(String summary) {
        assertThatThrownBy(() -> service.discharge(ADMISSION, new DischargeRequest(DischargeOutcome.HOME, summary)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-LT-14)");

        verifyNoInteractions(admissions, admissionOrders, visitQueries, visitOrders, kennels, pets, catalog);
        verify(scope, never()).check(any());
    }

    // ================================================================ thứ tự lỗi

    @Test
    void unknownAdmissionIsNotFoundAndNothingElseIsRead() {
        doReturn(Optional.empty()).when(admissions).findByIdForUpdate(ADMISSION);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home())).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        assertNothingElseTouched();
    }

    @Test
    void anotherBranchIsForbiddenBeforeTheAssigneeIsLookedUp() {
        locked(fromVisit());
        doThrow(new AccessDeniedScopeException("branch:" + BRANCH, "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home())).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(visitQueries);
        assertNothingWritten();
    }

    @Test
    void aVetWhoDoesNotRunTheVisitIsForbiddenEvenWhenTheAdmissionIsAlreadyDischarged() {
        Admission done = fromVisit();
        done.discharge(DischargeOutcome.HOME, "Ổn", VET, NOW_MICROS, LINE, 0);
        locked(done);
        doReturn(Optional.of(visit(OTHER_VET))).when(visitQueries).findVisit(VISIT);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home())).isInstanceOf(AccessDeniedScopeException.class);

        verify(transitions, never()).validateTransition(any(), any());
        assertNothingWritten();
    }

    @Test
    void aDischargedAdmissionIsAConflictAndWritesNothing() {
        Admission done = fromVisit();
        done.discharge(DischargeOutcome.HOME, "Ổn", VET, NOW_MICROS, LINE, 0);
        locked(done);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home()))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(admissionOrders);
        assertNothingWritten();
    }

    @Test
    void openOrdersBlockDischargeAndWriteNothing() {
        locked(fromVisit());
        doReturn(2L).when(admissionOrders).countOrderedByAdmissionId(ADMISSION);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("2 y lệnh").hasMessageEndingWith("(BR-LT-14)");

        assertNothingWritten();
    }

    @Test
    void missingVisitIsADataBugNotAnAuthorisationAnswer() {
        locked(fromVisit());
        doReturn(Optional.empty()).when(visitQueries).findVisit(VISIT);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home())).isInstanceOf(IllegalStateException.class);

        assertNothingWritten();
    }

    // ================================================================ thành công

    @Test
    void dischargeFromAVisitAddsOneLineWithTheLockedPriceReleasesTheKennelAndSavesLast() {
        Admission admission = fromVisit();
        locked(admission);

        AdmissionResponse response = service.discharge(ADMISSION, new DischargeRequest(DischargeOutcome.HOME, "  Ổn  "));

        InOrder order = inOrder(admissions, admissionOrders, visitOrders, kennels);
        order.verify(admissions).findByIdForUpdate(ADMISSION);
        order.verify(admissionOrders).countOrderedByAdmissionId(ADMISSION);
        // 22/11 và 23/11 giờ VN, gồm cả hai đầu = 2 ngày; giá của ca, không phải giá danh mục hôm nay
        order.verify(visitOrders).addServiceLine(VISIT, SERVICE, 2, LOCKED_PRICE, VET);
        order.verify(kennels).release(KENNEL);
        order.verify(admissions).saveAndFlush(admission);
        assertThat(admission.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(admission.getDischargedAt()).isEqualTo(NOW_MICROS);
        assertThat(admission.getOrderLineId()).isEqualTo(LINE);
        assertThat(response.status()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(response.dischargeOutcome()).isEqualTo(DischargeOutcome.HOME);
        assertThat(response.dischargeSummary()).isEqualTo("Ổn");
        assertThat(response.billedDays()).isEqualTo(2);
        assertThat(response.estimatedAmount()).isEqualTo(2 * LOCKED_PRICE);
        assertThat(response.unitPrice()).isEqualTo(LOCKED_PRICE);
        assertThat(response.orderLineId()).isEqualTo(LINE);
        assertThat(response.petName()).isEqualTo("Mochi");
    }

    @Test
    void dischargeFromAStayKeepsTheKennelAndTheBooking() {
        Admission admission = fromBoarding();
        locked(admission);

        service.discharge(ADMISSION, home());

        verify(visitOrders).addServiceLine(VISIT, SERVICE, 2, LOCKED_PRICE, VET);
        verifyNoInteractions(kennels);
        verify(admissions).saveAndFlush(admission);
        assertThat(admission.getBoardingBookingId()).isEqualTo(BOOKING);
    }

    @Test
    void deceasedOutcomeOnlyRecordsTheOutcome() {
        Admission admission = fromVisit();
        locked(admission);

        AdmissionResponse response = service.discharge(ADMISSION, new DischargeRequest(DischargeOutcome.DECEASED, "Mất"));

        assertThat(response.dischargeOutcome()).isEqualTo(DischargeOutcome.DECEASED);
        verify(visitOrders).addServiceLine(VISIT, SERVICE, 2, LOCKED_PRICE, VET);
        verify(kennels).release(KENNEL);
        // xuất viện không tự đánh dấu thú mất (BR-LT-14): chỉ đọc tên thú để trả response
        verify(pets, never()).findPetForShare(anyLong());
    }

    // ================================================================ lỗi giữa chừng: không lưu gì sau bước hỏng

    @Test
    void aFailingOrderLineStopsBeforeTheKennelAndTheSave() {
        locked(fromVisit());
        doThrow(new InvalidStateTransitionException("đơn hàng", "PENDING", "OPEN")).when(visitOrders)
                .addServiceLine(anyLong(), anyLong(), anyInt(), anyLong(), anyLong());

        assertThatThrownBy(() -> service.discharge(ADMISSION, home()))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(kennels);
        verify(admissions, never()).saveAndFlush(any());
    }

    @Test
    void aFailingKennelReleaseStopsBeforeTheSave() {
        locked(fromVisit());
        doThrow(new InvalidStateTransitionException("chuồng", "MAINTENANCE", "AVAILABLE")).when(kennels).release(KENNEL);

        assertThatThrownBy(() -> service.discharge(ADMISSION, home()))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(admissions, never()).saveAndFlush(any());
    }

    // ================================================================ transaction

    @Test
    void dischargeRunsInOneReadCommittedWriteTransactionWithoutNoRollbackFor() throws Exception {
        Method method = AdmissionDischargeService.class.getMethod("discharge", Long.class, DischargeRequest.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    private static DischargeRequest home() {
        return new DischargeRequest(DischargeOutcome.HOME, "Ổn định, cho về");
    }

    private void locked(Admission admission) {
        ReflectionTestUtils.setField(admission, "id", ADMISSION);
        doReturn(Optional.of(admission)).when(admissions).findByIdForUpdate(ADMISSION);
    }

    private static Admission fromVisit() {
        return Admission.admit("NT-2611-00007", VISIT, PET, BRANCH, null, KENNEL, SERVICE, LOCKED_PRICE, VET,
                ADMITTED_AT);
    }

    private static Admission fromBoarding() {
        return Admission.admit("NT-2611-00008", VISIT, PET, BRANCH, BOOKING, null, SERVICE, LOCKED_PRICE, VET,
                ADMITTED_AT);
    }

    private static VisitSummary visit(long assignee) {
        return new VisitSummary(VISIT, BRANCH, 88L, PET, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, assignee);
    }

    private void assertNothingWritten() {
        verify(visitOrders, never()).addServiceLine(anyLong(), anyLong(), anyInt(), anyLong(), anyLong());
        verify(kennels, never()).release(anyLong());
        verify(admissions, never()).saveAndFlush(any());
    }

    private void assertNothingElseTouched() {
        verifyNoInteractions(admissionOrders, visitQueries, visitOrders, kennels, pets, catalog);
    }
}
