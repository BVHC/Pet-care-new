package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.inpatient.dto.CreateObservationRequest;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.Appetite;
import com.petcare.module.inpatient.entity.DischargeOutcome;
import com.petcare.module.inpatient.mapper.AdmissionObservationMapper;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AdmissionObservationService} (inpatient-v1 #9; docs/adr/0149 mục 31–36). Thứ tự: khóa ca → 404 → 403 chi nhánh →
 * 409 trạng thái → 400 {@code BR-LT-16} → ghi → cân nặng → thông báo; mỗi nhánh lỗi không ghi gì. Hành vi trên DB thật,
 * khóa chờ, rollback và outbox ở {@code AdmissionObservation*IT}.
 */
class AdmissionObservationServiceTest {

    private static final long BRANCH = 3L;
    private static final long RECORDER = 45L;
    private static final long ADMISSION = 51L;
    private static final long PET = 12L;
    private static final long VISIT = 9001L;
    private static final long OBSERVATION = 301L;
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-22T02:00:00Z");
    /** Giá trị DB đã lưu (µs): response phải dùng nó, không dùng mốc nano của entity. */
    private static final Instant STORED_AT = Instant.parse("2026-11-23T03:15:30.123456Z");

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final AdmissionObservationRepository observations = mock(AdmissionObservationRepository.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final PetApi petApi = mock(PetApi.class);
    private final AdmissionObservationNotices notices = mock(AdmissionObservationNotices.class);
    private final AdmissionObservationService service = new AdmissionObservationService(scope, admissions,
            observations, staff, petApi, notices, Mappers.getMapper(AdmissionObservationMapper.class));

    private Admission admission;

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(RECORDER).when(principal).accountId();
        admission = admission();
        doReturn(Optional.of(admission)).when(admissions).findByIdForUpdate(ADMISSION);
        doReturn(Optional.of(new StaffSummary(RECORDER, "Trần Văn Minh", Role.CARETAKER, BRANCH, true, true))).when(staff)
                .findStaff(RECORDER);
        doAnswer(invocation -> {
            AdmissionObservation saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", OBSERVATION);
            return saved;
        }).when(observations).saveAndFlush(any(AdmissionObservation.class));
        doReturn(STORED_AT).when(observations).findCreatedAt(OBSERVATION);
    }

    // ---------------------------------------------------------------- luồng thành công

    @Test
    void locksTheAdmissionBeforeAnythingIsWrittenAndWritesInTheDocumentedOrder() {
        service.create(ADMISSION, request(new BigDecimal("5.55"), true));

        InOrder inOrder = inOrder(admissions, scope, staff, observations, petApi, notices);
        inOrder.verify(admissions).findByIdForUpdate(ADMISSION);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(staff).findStaff(RECORDER);
        inOrder.verify(observations).saveAndFlush(any(AdmissionObservation.class));
        inOrder.verify(observations).findCreatedAt(OBSERVATION);
        inOrder.verify(petApi).recordWeight(anyLong(), any(), any(), any(), any(), any());
        inOrder.verify(notices).enqueue(any(), any(), anyLong());
    }

    @Test
    void theResponseCarriesTheStoredTimestampTheRecorderNameAndTheRoundedValues() {
        ObservationResponse response = service.create(ADMISSION,
                new CreateObservationRequest(new BigDecimal("38.56"), 120, 30, new BigDecimal("5.555"),
                        Appetite.REDUCED, "  Ăn ít  ", false));

        assertThat(response.observationId()).isEqualTo(OBSERVATION);
        assertThat(response.admissionId()).isEqualTo(ADMISSION);
        assertThat(response.recordedByName()).isEqualTo("Trần Văn Minh");
        assertThat(response.createdAt()).isEqualTo(STORED_AT);
        assertThat(response.temperatureC()).isEqualByComparingTo("38.6");
        assertThat(response.weightKg()).isEqualByComparingTo("5.56");
        assertThat(response.heartRate()).isEqualTo(120);
        assertThat(response.respiratoryRate()).isEqualTo(30);
        assertThat(response.appetite()).isEqualTo(Appetite.REDUCED);
        assertThat(response.note()).isEqualTo("Ăn ít");
        assertThat(response.abnormal()).isFalse();
    }

    @Test
    void theRecorderIsTheCallerAndNeverTheAssigneeOrAnythingFromTheBody() {
        service.create(ADMISSION, request(null, false));

        verify(observations).saveAndFlush(org.mockito.ArgumentMatchers.<AdmissionObservation>argThat(
                o -> o.getRecordedBy().equals(RECORDER) && o.getAdmissionId().equals(ADMISSION)));
    }

    @Test
    void aWeightIsAlsoRecordedInThePetWeightHistoryWithTheVetSourceTheVisitOfTheCaseAndTheCaller() {
        service.create(ADMISSION, request(new BigDecimal("5.555"), false));

        // đã làm tròn như cột (5.56); nguồn VET gắn Visit của ca, không gắn đặt chỗ; người ghi là người gọi
        verify(petApi).recordWeight(eq(PET), eq(new BigDecimal("5.56")), eq(WeightSource.VET), eq(VISIT), isNull(),
                eq(RECORDER));
    }

    @Test
    void withoutAWeightThePetWeightHistoryIsNotTouched() {
        service.create(ADMISSION, request(null, false));

        verifyNoInteractions(petApi);
    }

    @Test
    void aNormalObservationSendsNoNotificationAndAnAbnormalOneSendsOnceWithTheStoredTimestamp() {
        service.create(ADMISSION, request(null, false));
        verifyNoInteractions(notices);

        service.create(ADMISSION, request(null, true));
        verify(notices).enqueue(admission, STORED_AT, RECORDER);
    }

    // ---------------------------------------------------------------- lỗi: không ghi gì

    @Test
    void aMissingAdmissionIs404WithoutAnyCheckOrWrite() {
        doReturn(Optional.empty()).when(admissions).findByIdForUpdate(404L);

        assertThatThrownBy(() -> service.create(404L, request(null, true))).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        assertNothingWritten();
    }

    @Test
    void anotherBranchIs403BeforeAnyOtherCheckOrWrite() {
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.create(ADMISSION, request(null, true)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(staff);
        assertNothingWritten();
    }

    @Test
    void aDischargedAdmissionIs409AndTheStateCheckComesBeforeTheValueCheck() {
        admission.discharge(DischargeOutcome.HOME, "Xong", RECORDER, ADMITTED_AT.plusSeconds(60), 55L, 0);
        // dữ liệu sai (không chỉ số nào) mà ca đã xuất viện: 409 đứng trước 400 (khuôn y lệnh, ADR-0149 mục 26)
        CreateObservationRequest empty = new CreateObservationRequest(null, null, null, null, null, null, true);

        assertThatThrownBy(() -> service.create(ADMISSION, empty)).isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(staff);
        assertNothingWritten();
    }

    @Test
    void anObservationWithNothingInItIsRejectedAfterTheStateCheck() {
        CreateObservationRequest empty = new CreateObservationRequest(null, null, null, null, null, "  ", true);

        assertThatThrownBy(() -> service.create(ADMISSION, empty)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-LT-16)");

        verify(scope).check(BRANCH);
        assertNothingWritten();
    }

    @Test
    void aValueOutsideTheStorageLimitsIsRejectedWithoutWritingAnything() {
        for (CreateObservationRequest bad : new CreateObservationRequest[] {
                new CreateObservationRequest(new BigDecimal("1000"), null, null, null, null, null, false),
                new CreateObservationRequest(null, 0, null, null, null, null, false),
                new CreateObservationRequest(null, null, 32_768, null, null, null, false),
                new CreateObservationRequest(null, null, null, BigDecimal.ZERO, null, null, false),
                new CreateObservationRequest(null, null, null, new BigDecimal("10000"), null, null, false)}) {
            assertThatThrownBy(() -> service.create(ADMISSION, bad)).isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageEndingWith("(BR-LT-16)");
        }

        assertNothingWritten();
    }

    @Test
    void aMissingRecorderProfileIsCorruptDataAndNothingIsWritten() {
        doReturn(Optional.empty()).when(staff).findStaff(RECORDER);

        assertThatThrownBy(() -> service.create(ADMISSION, request(null, true))).isInstanceOf(IllegalStateException.class);

        assertNothingWritten();
    }

    @Test
    void ifSavingFailsNeitherTheWeightNorTheNotificationIsWritten() {
        doThrow(new IllegalStateException("db")).when(observations).saveAndFlush(any(AdmissionObservation.class));

        assertThatThrownBy(() -> service.create(ADMISSION, request(new BigDecimal("5"), true)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(petApi, notices);
    }

    @Test
    void ifTheWeightFailsTheNotificationIsNotQueued() {
        doThrow(new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất")).when(petApi)
                .recordWeight(anyLong(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.create(ADMISSION, request(new BigDecimal("5"), true)))
                .isInstanceOf(BusinessRuleViolationException.class);

        verifyNoInteractions(notices);
    }

    // ---------------------------------------------------------------- giao dịch

    @Test
    void createIsOneRequiredTransactionThatRollsBackOnEveryRuntimeException() throws Exception {
        Transactional tx = AdmissionObservationService.class
                .getMethod("create", Long.class, CreateObservationRequest.class).getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.rollbackFor()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void assertNothingWritten() {
        verify(observations, never()).saveAndFlush(any());
        verifyNoInteractions(petApi, notices);
    }

    private static CreateObservationRequest request(BigDecimal weight, boolean abnormal) {
        return new CreateObservationRequest(new BigDecimal("38.6"), 120, null, weight, null, "Ổn", abnormal);
    }

    private static Admission admission() {
        Admission a = Admission.admit("NT-2611-00051", VISIT, PET, BRANCH, null, 17L, 41L, 200_000L, 7L, ADMITTED_AT);
        ReflectionTestUtils.setField(a, "id", ADMISSION);
        return a;
    }
}
