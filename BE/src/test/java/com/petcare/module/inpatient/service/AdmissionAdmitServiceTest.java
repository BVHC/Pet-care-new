package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmitRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.fsm.AdmissionTransitionHandler;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.module.visit.api.VisitApi;
import com.petcare.module.visit.api.VisitApi.TreatmentVisitRef;
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
import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * {@link AdmissionAdmitService} (inpatient-v1 #1, docs/adr/0149 mục 16–18): nguồn sai bị chặn trước khi chạm DB; 404 →
 * 403 chi nhánh trước mọi khóa; khóa {@code pets} → lượt / đặt chỗ → 409 trùng ca → guard 400 → dịch vụ → ghi; mỗi nhánh
 * lỗi không chiếm chuồng, không tạo Visit điều trị, không lưu ca. Server lấy thú / chi nhánh / giá, không lấy từ body.
 * Hành vi trên DB thật, rollback và race ở {@code AdmissionAdmit*IT}.
 */
class AdmissionAdmitServiceTest {

    private static final long BRANCH = 3L;
    private static final long OTHER_BRANCH = 9L;
    private static final long VET = 45L;
    private static final long PET = 12L;
    private static final long CUSTOMER = 88L;
    private static final long VISIT = 9001L;
    private static final long BOOKING = 301L;
    private static final long SERVICE = 41L;
    private static final long KENNEL = 17L;
    private static final long PRICE = 250_000L;
    private static final Instant NOW = Instant.parse("2026-11-23T03:15:30.123456789Z");
    private static final Instant NOW_MICROS = NOW.truncatedTo(ChronoUnit.MICROS);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitQueryApi visitQueries = mock(VisitQueryApi.class);
    private final VisitApi visits = mock(VisitApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class);
    private final KennelApi kennels = mock(KennelApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final AdmissionTransitionHandler transitions = spy(new AdmissionTransitionHandler());
    private final DocumentCodeGenerator codes = mock(DocumentCodeGenerator.class);
    private final AdmissionAdmitService service = new AdmissionAdmitService(scope, visitQueries, visits, pets, boarding,
            kennels, catalog, branches, admissions, transitions, codes, Mappers.getMapper(AdmissionMapper.class),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn("NT-2611-00007").when(codes).next(anyString(), anyString(), any(LocalDate.class));
        doAnswer(inv -> {
            Admission admission = inv.getArgument(0);
            ReflectionTestUtils.setField(admission, "id", 51L);
            return admission;
        }).when(admissions).saveAndFlush(any(Admission.class));
        doReturn(Optional.of(pet(false))).when(pets).findPetForShare(PET);
        doReturn(false).when(admissions).existsActiveByPetId(PET);
        doReturn(Optional.empty()).when(boarding).findCurrentStay(PET);
        doReturn(Optional.of(new ServiceInfo(SERVICE, "Nội trú điều trị", ServiceGroup.MEDICAL, MedicalType.INPATIENT,
                PRICE, true))).when(catalog).findService(SERVICE);
        doReturn(true).when(branches).isServiceEnabled(BRANCH, SERVICE);
    }

    // ================================================================ nguồn sai: chưa chạm DB

    @Test
    void bothSourcesAreRejectedBeforeAnyRead() {
        assertRule(() -> service.admit(new AdmitRequest(VISIT, BOOKING, SERVICE, KENNEL)));
        assertNoDataTouched();
    }

    @Test
    void noSourceIsRejectedBeforeAnyRead() {
        assertRule(() -> service.admit(new AdmitRequest(null, null, SERVICE, KENNEL)));
        assertNoDataTouched();
    }

    @Test
    void fromVisitWithoutAKennelIsRejectedBeforeAnyRead() {
        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, null)));
        assertNoDataTouched();
    }

    @Test
    void fromBoardingWithAKennelIsRejectedBeforeAnyRead() {
        assertRule(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, KENNEL)));
        assertNoDataTouched();
    }

    // ================================================================ đường (a): từ lượt khám

    @Test
    void fromVisitLocksPetThenVisitThenChecksThenOccupiesTheKennelThenSaves() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);

        AdmissionResponse response = service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL));

        InOrder order = inOrder(visitQueries, scope, pets, visits, admissions, boarding, catalog, branches, transitions,
                kennels, codes);
        order.verify(visitQueries).findVisit(VISIT);
        order.verify(scope).check(BRANCH);
        order.verify(pets).findPetForShare(PET);
        order.verify(visits).lockVisitForAdmission(VISIT);
        order.verify(admissions).existsActiveByPetId(PET);
        order.verify(boarding).findCurrentStay(PET);
        order.verify(catalog).findService(SERVICE);
        order.verify(branches).isServiceEnabled(BRANCH, SERVICE);
        order.verify(transitions).validateInitial(AdmissionStatus.ADMITTED);
        order.verify(kennels).occupyForTreatment(KENNEL, BRANCH);
        order.verify(codes).next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.of(2026, 11, 23));
        order.verify(admissions).saveAndFlush(any(Admission.class));
        verify(visits, never()).startTreatmentVisit(anyLong(), anyLong(), anyLong(), anyLong());

        Admission saved = captured();
        assertThat(saved.getVisitId()).isEqualTo(VISIT);
        assertThat(saved.getPetId()).isEqualTo(PET);
        assertThat(saved.getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getKennelId()).isEqualTo(KENNEL);
        assertThat(saved.getBoardingBookingId()).isNull();
        assertThat(saved.getUnitPrice()).isEqualTo(PRICE);
        assertThat(saved.getAdmittedBy()).isEqualTo(VET);
        assertThat(saved.getAdmittedAt()).isEqualTo(NOW_MICROS);
        assertThat(saved.getStatus()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(response.admissionId()).isEqualTo(51L);
        assertThat(response.petName()).isEqualTo("Mochi");
        assertThat(response.serviceName()).isEqualTo("Nội trú điều trị");
        assertThat(response.billedDays()).isEqualTo(1);
        assertThat(response.estimatedAmount()).isEqualTo(PRICE);
    }

    @Test
    void fromVisitMissingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visitQueries).findVisit(VISIT);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, visits);
        assertNothingWritten();
    }

    @Test
    void fromVisitOtherBranchIs403BeforeAnyLock() {
        visitFound(OTHER_BRANCH);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, visits);
        assertNothingWritten();
    }

    @Test
    void fromVisitPetAlreadyAdmittedIs409BeforeTheRuleChecks() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.COMPLETED, VET + 1, ServiceGroup.GROOMING);
        doReturn(true).when(admissions).existsActiveByPetId(PET);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("đã có ca đang điều trị");

        verifyNoInteractions(boarding, catalog);
        assertNothingWritten();
    }

    @Test
    void fromVisitDeceasedPetIsRejected() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(Optional.of(pet(true))).when(pets).findPetForShare(PET);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void fromVisitNotInProgressIsRejected(VisitStatus status) {
        visitFound(BRANCH);
        lockedVisit(status, VET, ServiceGroup.MEDICAL);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void fromVisitNotTheCurrentAssigneeIsRejectedAfterTheLock() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET + 1, ServiceGroup.MEDICAL);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void fromVisitGroomingVisitIsRejected() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.GROOMING);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void fromVisitPetInABoardingStayMustUseTheBoardingPath() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(Optional.of(stay(StayStatus.CHECKED_IN))).when(boarding).findCurrentStay(PET);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void kennelFailureIsNotSwallowedAndNothingIsSaved() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        BusinessRuleViolationException failure = new BusinessRuleViolationException("BR-LT-13", "Chuồng không còn trống");
        doThrow(failure).when(kennels).occupyForTreatment(KENNEL, BRANCH);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL))).isSameAs(failure);

        verifyNoInteractions(codes);
        verify(admissions, never()).saveAndFlush(any());
    }

    // ================================================================ dịch vụ (BR-LT-13, BR-LT-18, A5)

    @Test
    void missingServiceIs404AndNothingIsWritten() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(Optional.empty()).when(catalog).findService(SERVICE);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertNothingWritten();
    }

    @ParameterizedTest
    @EnumSource(value = MedicalType.class, names = {"EXAM", "VACCINE"})
    void aServiceThatIsNotInpatientIsRejected(MedicalType type) {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(Optional.of(new ServiceInfo(SERVICE, "Khám", ServiceGroup.MEDICAL, type, PRICE, true)))
                .when(catalog).findService(SERVICE);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void anInactiveInpatientServiceIsRejected() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(Optional.of(new ServiceInfo(SERVICE, "Nội trú", ServiceGroup.MEDICAL, MedicalType.INPATIENT, PRICE,
                false))).when(catalog).findService(SERVICE);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    @Test
    void aServiceDisabledAtTheBranchIsRejected() {
        visitFound(BRANCH);
        lockedVisit(VisitStatus.IN_PROGRESS, VET, ServiceGroup.MEDICAL);
        doReturn(false).when(branches).isServiceEnabled(BRANCH, SERVICE);

        assertRule(() -> service.admit(new AdmitRequest(VISIT, null, SERVICE, KENNEL)));
        assertNothingWritten();
    }

    // ================================================================ đường (b): từ lưu trú

    @ParameterizedTest
    @EnumSource(value = StayStatus.class, names = {"CHECKED_IN", "OVERDUE"})
    void fromBoardingLocksPetThenBookingThenStartsTheTreatmentVisitThenSaves(StayStatus status) {
        bookingFound(BRANCH);
        doReturn(Optional.of(stay(status))).when(boarding).findBookingForShare(BOOKING);
        doReturn(new TreatmentVisitRef(9100L, 7100L)).when(visits).startTreatmentVisit(PET, CUSTOMER, BRANCH, VET);

        AdmissionResponse response = service.admit(new AdmitRequest(null, BOOKING, SERVICE, null));

        InOrder order = inOrder(boarding, scope, pets, admissions, catalog, branches, transitions, visits, codes);
        order.verify(boarding).findBooking(BOOKING);
        order.verify(scope).check(BRANCH);
        order.verify(pets).findPetForShare(PET);
        order.verify(boarding).findBookingForShare(BOOKING);
        order.verify(admissions).existsActiveByPetId(PET);
        order.verify(catalog).findService(SERVICE);
        order.verify(branches).isServiceEnabled(BRANCH, SERVICE);
        order.verify(transitions).validateInitial(AdmissionStatus.ADMITTED);
        order.verify(visits).startTreatmentVisit(PET, CUSTOMER, BRANCH, VET);
        order.verify(codes).next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.of(2026, 11, 23));
        order.verify(admissions).saveAndFlush(any(Admission.class));
        verifyNoInteractions(kennels, visitQueries);
        verify(boarding, never()).findCurrentStay(anyLong());

        Admission saved = captured();
        assertThat(saved.getVisitId()).isEqualTo(9100L);
        assertThat(saved.getBoardingBookingId()).isEqualTo(BOOKING);
        assertThat(saved.getKennelId()).isNull();
        assertThat(saved.getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getUnitPrice()).isEqualTo(PRICE);
        assertThat(response.visitId()).isEqualTo(9100L);
        assertThat(response.boardingBookingId()).isEqualTo(BOOKING);
    }

    @Test
    void fromBoardingMissingBookingIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(boarding).findBooking(BOOKING);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(pets);
        assertNothingWritten();
    }

    @Test
    void fromBoardingOtherBranchIs403BeforeAnyLock() {
        bookingFound(OTHER_BRANCH);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets);
        verify(boarding, never()).findBookingForShare(anyLong());
        assertNothingWritten();
    }

    @ParameterizedTest
    @EnumSource(value = StayStatus.class, names = {"BOOKED", "CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void fromBoardingABookingNotHoldingThePetIsRejectedAfterTheLock(StayStatus status) {
        bookingFound(BRANCH);
        doReturn(Optional.of(stay(status))).when(boarding).findBookingForShare(BOOKING);

        assertRule(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null)));
        assertNothingWritten();
    }

    @Test
    void fromBoardingPetAlreadyAdmittedIs409() {
        bookingFound(BRANCH);
        doReturn(Optional.of(stay(StayStatus.CHECKED_IN))).when(boarding).findBookingForShare(BOOKING);
        doReturn(true).when(admissions).existsActiveByPetId(PET);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertNothingWritten();
    }

    @Test
    void fromBoardingDeceasedPetIsRejected() {
        bookingFound(BRANCH);
        doReturn(Optional.of(stay(StayStatus.OVERDUE))).when(boarding).findBookingForShare(BOOKING);
        doReturn(Optional.of(pet(true))).when(pets).findPetForShare(PET);

        assertRule(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null)));
        assertNothingWritten();
    }

    @Test
    void treatmentVisitFailureIsNotSwallowedAndNothingIsSaved() {
        bookingFound(BRANCH);
        doReturn(Optional.of(stay(StayStatus.CHECKED_IN))).when(boarding).findBookingForShare(BOOKING);
        IllegalStateException failure = new IllegalStateException("boom");
        doThrow(failure).when(visits).startTreatmentVisit(PET, CUSTOMER, BRANCH, VET);

        assertThatThrownBy(() -> service.admit(new AdmitRequest(null, BOOKING, SERVICE, null))).isSameAs(failure);

        verifyNoInteractions(codes);
        verify(admissions, never()).saveAndFlush(any());
    }

    // ================================================================ transaction

    @Test
    void admitRunsInOneReadCommittedWriteTransactionWithoutNoRollbackFor() throws Exception {
        Method method = AdmissionAdmitService.class.getMethod("admit", AdmitRequest.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    private void visitFound(long branchId) {
        doReturn(Optional.of(new VisitSummary(VISIT, branchId, CUSTOMER, PET, ServiceGroup.MEDICAL,
                VisitStatus.IN_PROGRESS, VET))).when(visitQueries).findVisit(VISIT);
    }

    private void lockedVisit(VisitStatus status, long assignee, ServiceGroup group) {
        doReturn(Optional.of(new VisitSummary(VISIT, BRANCH, CUSTOMER, PET, group, status, assignee)))
                .when(visits).lockVisitForAdmission(VISIT);
    }

    private void bookingFound(long branchId) {
        doReturn(Optional.of(new StaySummary(BOOKING, branchId, CUSTOMER, PET, StayStatus.CHECKED_IN, null)))
                .when(boarding).findBooking(BOOKING);
    }

    private static StaySummary stay(StayStatus status) {
        return new StaySummary(BOOKING, BRANCH, CUSTOMER, PET, status, "0900000000");
    }

    private static PetSummary pet(boolean deceased) {
        return new PetSummary(PET, CUSTOMER, "Mochi", Species.DOG, null,
                deceased ? LocalDate.of(2026, 11, 20) : null, null);
    }

    private Admission captured() {
        ArgumentCaptor<Admission> saved = ArgumentCaptor.forClass(Admission.class);
        verify(admissions).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private void assertNoDataTouched() {
        verifyNoInteractions(visitQueries, visits, pets, boarding, kennels, catalog, branches, admissions, codes);
        verify(scope, never()).check(any());
    }

    private void assertNothingWritten() {
        verify(kennels, never()).occupyForTreatment(anyLong(), anyLong());
        verify(visits, never()).startTreatmentVisit(anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(codes);
        verify(admissions, never()).saveAndFlush(any());
    }

    private static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-LT-13)");
    }
}
