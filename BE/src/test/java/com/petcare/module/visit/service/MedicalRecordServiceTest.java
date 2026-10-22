package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.dto.MedicalRecordResponse;
import com.petcare.module.visit.dto.RecordWeightRequest;
import com.petcare.module.visit.dto.UpdateMedicalRecordRequest;
import com.petcare.module.visit.dto.WeightRecordRefResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordAddendumRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link MedicalRecordService} (docs/adr/0065): role trước mọi lần đọc, 404 → 403 chi nhánh → 404 GROOMING trước khi
 * khóa, khóa thú → lượt → người phụ trách (403) → trạng thái (409) → đọc bệnh án → BR-KB-06 (400) → ghi → audit,
 * nhánh lỗi không ghi gì, PUT không đổi không ghi. Hành vi trên DB thật ở {@code MedicalRecordIT}.
 */
class MedicalRecordServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET_A = 45L;
    private static final long VET_B = 46L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    /** 02:00Z = 09:00 giờ Việt Nam, ngày khám 2026-10-21. */
    private static final Instant CHECKED_IN = Instant.parse("2026-10-21T02:00:00Z");
    private static final LocalDate EXAM_DAY = LocalDate.of(2026, 10, 21);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final PetApi petApi = mock(PetApi.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final SystemConfigApi config = mock(SystemConfigApi.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final MedicalRecordRepository records = mock(MedicalRecordRepository.class);
    private final MedicalRecordAddendumRepository addenda = mock(MedicalRecordAddendumRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final MedicalRecordService service = new MedicalRecordService(scope, pets, petApi, staff, config, visits, records,
            addenda, audit, Mappers.getMapper(MedicalRecordMapper.class));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        asVet(VET_A);
        doReturn(180).when(config).getInt(ConfigKey.MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS);
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mực", Species.DOG, null, null, null))).when(pets)
                .findPetForShare(PET);
    }

    // ================================================================ #12 xem

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void viewRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.getMedicalRecord(VISIT)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, records, addenda, staff);
    }

    @Test
    void viewOfMissingVisitIs404() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.getMedicalRecord(VISIT)).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(records);
    }

    @Test
    void viewOfOtherBranchIs403BeforeReadingTheRecord() {
        summary(9L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.getMedicalRecord(VISIT)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(records);
    }

    @Test
    void viewOfGroomingVisitIs404() {
        summary(BRANCH, ServiceGroup.GROOMING, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.getMedicalRecord(VISIT)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(records);
    }

    @Test
    void viewOfAVisitWithoutRecordReturnsAnEmptyFrameWithoutLocking() {
        asCaretaker(47L);
        summary(BRANCH, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        MedicalRecordResponse response = service.getMedicalRecord(VISIT);

        assertThat(response.visitId()).isEqualTo(VISIT);
        assertThat(response.locked()).isFalse();
        assertThat(response.diagnosis()).isNull();
        assertThat(response.addenda()).isEmpty();
        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(addenda, audit, pets);
    }

    @Test
    void viewOfAnOpenVisitDoesNotReadAddenda() {
        summary(BRANCH, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(recordWith("khám", "dx", null))).when(records).findById(VISIT);

        MedicalRecordResponse response = service.getMedicalRecord(VISIT);

        assertThat(response.locked()).isFalse();
        assertThat(response.diagnosis()).isEqualTo("dx");
        verifyNoInteractions(addenda, staff);
    }

    @Test
    void viewOfACompletedVisitIsLockedAndCarriesAddendaWithAuthorNames() {
        summary(BRANCH, ServiceGroup.MEDICAL, VisitStatus.COMPLETED);
        doReturn(Optional.of(recordWith("khám", "dx", null))).when(records).findById(VISIT);
        MedicalRecordAddendum first = MedicalRecordAddendum.of(VISIT, "bổ sung 1", false, VET_A);
        MedicalRecordAddendum second = MedicalRecordAddendum.of(VISIT, "bổ sung 2", true, VET_B);
        doReturn(List.of(first, second)).when(addenda).findByMedicalRecordIdOrderByIdAsc(VISIT);
        doReturn(List.of(new StaffSummary(VET_A, "BS A", Role.VET, BRANCH, true, true),
                new StaffSummary(VET_B, "BS B", Role.VET, BRANCH, true, true))).when(staff).findStaffByIds(any());

        MedicalRecordResponse response = service.getMedicalRecord(VISIT);

        assertThat(response.locked()).isTrue();
        assertThat(response.addenda()).extracting("content", "isInternal", "createdByName")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("bổ sung 1", false, "BS A"),
                        org.assertj.core.groups.Tuple.tuple("bổ sung 2", true, "BS B"));
    }

    @Test
    void anAddendumAuthorMissingFromTheDirectoryIsCorruptData() {
        summary(BRANCH, ServiceGroup.MEDICAL, VisitStatus.COMPLETED);
        doReturn(Optional.of(recordWith("khám", "dx", null))).when(records).findById(VISIT);
        doReturn(List.of(MedicalRecordAddendum.of(VISIT, "x", false, VET_B))).when(addenda)
                .findByMedicalRecordIdOrderByIdAsc(VISIT);
        doReturn(List.of()).when(staff).findStaffByIds(any());

        assertThatThrownBy(() -> service.getMedicalRecord(VISIT)).isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ #13 ghi

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void writeRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, pets, records, audit);
    }

    @Test
    void writeOfMissingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(pets, records, audit);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void writeOfOtherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, records, audit);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void writeOfGroomingVisitIs404AndCreatesNoRecord() {
        summary(BRANCH, ServiceGroup.GROOMING, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(pets, records, audit);
    }

    @Test
    void firstWriteLocksPetThenVisitThenReadsThenWritesThenAudits() {
        Visit visit = stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        MedicalRecordResponse response = service.updateMedicalRecord(VISIT,
                request("  khám  ", "viêm", "điều trị", "nội bộ", EXAM_DAY.plusDays(14)));

        InOrder order = inOrder(pets, visits, records, audit);
        order.verify(pets).findPetForShare(PET);
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(records).findById(VISIT);
        order.verify(records).saveAndFlush(any(MedicalRecord.class));
        order.verify(audit).record(any(AuditEntry.class));

        ArgumentCaptor<MedicalRecord> saved = ArgumentCaptor.forClass(MedicalRecord.class);
        verify(records).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getVisitId()).isEqualTo(visit.getId());
        assertThat(saved.getValue().getLastEditedBy()).isEqualTo(VET_A);
        assertThat(saved.getValue().getFollowUpDate()).isEqualTo(EXAM_DAY.plusDays(14));
        assertThat(response.locked()).isFalse();
        assertThat(response.prescriptions()).isEmpty();
        assertThat(response.addenda()).isEmpty();
        assertThat(response.followUpDate()).isEqualTo(EXAM_DAY.plusDays(14));
    }

    @Test
    void firstAuditHasNoBeforeAndTheFiveFieldsAfter() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        service.updateMedicalRecord(VISIT, request("khám", null, null, "nội bộ", null));

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("MEDICAL_RECORD_UPDATED");
        assertThat(entry.entityType()).isEqualTo("medical_records");
        assertThat(entry.entityId()).isEqualTo(VISIT);
        assertThat(entry.before()).isNull();
        assertThat(entry.after()).isInstanceOf(java.util.Map.class);
        assertThat(new java.util.ArrayList<Object>(((java.util.Map<?, ?>) entry.after()).keySet()))
                .containsExactly("examination", "diagnosis", "treatmentPlan", "internalNote", "followUpDate");
    }

    @Test
    void emptyFirstWriteStillCreatesTheRecord() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        service.updateMedicalRecord(VISIT, request(null, null, null, null, null));

        verify(records).saveAndFlush(any(MedicalRecord.class));
    }

    @Test
    void rewriteReplacesAllFieldsAndAuditsBeforeAndAfter() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        MedicalRecord existing = recordWith("khám cũ", "dx cũ", EXAM_DAY.plusDays(7));
        ReflectionTestUtils.setField(existing, "lastEditedBy", VET_B);
        doReturn(Optional.of(existing)).when(records).findById(VISIT);

        service.updateMedicalRecord(VISIT, request("khám mới", null, null, null, EXAM_DAY.plusDays(7)));

        assertThat(existing.getExamination()).isEqualTo("khám mới");
        assertThat(existing.getDiagnosis()).as("trường vắng là xóa").isNull();
        assertThat(existing.getLastEditedBy()).isEqualTo(VET_A);
        AuditEntry entry = capturedAudit();
        assertThat(((java.util.Map<?, ?>) entry.before()).get("examination")).isEqualTo("khám cũ");
        assertThat(((java.util.Map<?, ?>) entry.after()).get("examination")).isEqualTo("khám mới");
        assertThat(((java.util.Map<?, ?>) entry.before()).get("followUpDate")).isEqualTo("2026-10-28");
    }

    @Test
    void identicalContentWritesAndAuditsNothing() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        MedicalRecord existing = recordWith("khám", "dx", EXAM_DAY.plusDays(7));
        ReflectionTestUtils.setField(existing, "lastEditedBy", VET_B);
        doReturn(Optional.of(existing)).when(records).findById(VISIT);

        MedicalRecordResponse response = service.updateMedicalRecord(VISIT,
                request("khám", "dx", null, null, EXAM_DAY.plusDays(7)));

        verify(records, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
        assertThat(existing.getLastEditedBy()).as("không đổi người sửa").isEqualTo(VET_B);
        assertThat(response.diagnosis()).isEqualTo("dx");
    }

    @Test
    void otherVetOfTheSameBranchIs403AfterTheLockAndWritesNothing() {
        stubLoaded(VET_B, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        InOrder order = inOrder(pets, visits);
        order.verify(pets).findPetForShare(PET);
        order.verify(visits).lockById(VISIT);
        verifyNoInteractions(records, audit);
    }

    @Test
    void unassignedVisitIs403() {
        stubLoaded(null, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(records, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING", "COMPLETED", "CANCELLED"})
    void visitNotInProgressIs409AndWritesNothing(String status) {
        stubLoaded(VET_A, VisitStatus.valueOf(status));

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(records, audit);
    }

    @Test
    void ownershipIsCheckedBeforeStatus() {
        stubLoaded(VET_B, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    // ================================================================ #14 cân nặng

    private static final Instant WEIGHED_AT = Instant.parse("2026-10-22T03:00:00.123456Z");

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void weightRolesOutsideTheContractNeverReachTheRepository(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, pets, petApi);
    }

    @Test
    void weightOfMissingVisitIs404BeforeAnyLock() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(pets, petApi);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void weightOfOtherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, petApi);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void weightOfGroomingVisitIs404AndWritesNothing() {
        summary(BRANCH, ServiceGroup.GROOMING, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(pets, petApi);
    }

    @Test
    void weightLocksPetThenVisitThenRecordsAsVetWithTheServerSideIds() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(new RecordedWeight(812L, new BigDecimal("12.35"), WEIGHED_AT)).when(petApi).recordWeight(PET,
                new BigDecimal("12.346"), WeightSource.VET, VISIT, null, VET_A);

        WeightRecordRefResponse response = service.recordWeight(VISIT, new RecordWeightRequest(new BigDecimal("12.346")));

        InOrder order = inOrder(pets, visits, petApi);
        order.verify(pets).findPetForShare(PET);
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(petApi).recordWeight(PET, new BigDecimal("12.346"), WeightSource.VET, VISIT, null, VET_A);
        assertThat(response.weightRecordId()).isEqualTo(812L);
        assertThat(response.weightKg()).isEqualByComparingTo("12.35");
        assertThat(response.measuredAt()).isEqualTo(WEIGHED_AT);
        verifyNoInteractions(records, audit);
    }

    @Test
    void otherVetOfTheSameBranchOrUnassignedVisitIs403AfterTheLockAndRecordsNothing() {
        stubLoaded(VET_B, VisitStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedScopeException.class);
        stubLoaded(null, VisitStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(petApi);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING", "COMPLETED", "CANCELLED"})
    void weightOnAVisitNotInProgressIs409AndRecordsNothing(String status) {
        stubLoaded(VET_A, VisitStatus.valueOf(status));

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(petApi);
    }

    @Test
    void weightOwnershipIsCheckedBeforeStatus() {
        stubLoaded(VET_B, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void ruleViolationFromTheCustomerApiPropagatesUnchanged() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doThrow(new BusinessRuleViolationException("BR-KH-04", "Cân nặng phải lớn hơn 0")).when(petApi)
                .recordWeight(PET, BigDecimal.ZERO, WeightSource.VET, VISIT, null, VET_A);

        assertThatThrownBy(() -> service.recordWeight(VISIT, new RecordWeightRequest(BigDecimal.ZERO)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-04");
    }

    // ---- BR-KB-06: sau ngày khám, tối đa max ngày; ngày khám = ngày check-in theo giờ Việt Nam

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 181, 400})
    void followUpOutsideTheWindowIs400AndWritesNothing(long offset) {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT,
                request("x", "y", null, null, EXAM_DAY.plusDays(offset))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KB-06")
                .hasMessageContaining("180");

        verify(records, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 90, 180})
    void followUpInsideTheWindowIsAccepted(long offset) {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);

        service.updateMedicalRecord(VISIT, request("x", "y", null, null, EXAM_DAY.plusDays(offset)));

        verify(records).saveAndFlush(any(MedicalRecord.class));
    }

    @Test
    void windowUsesTheConfiguredMaximumReadAtWriteTime() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);
        doReturn(30).when(config).getInt(ConfigKey.MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT,
                request("x", "y", null, null, EXAM_DAY.plusDays(31)))).isInstanceOf(BusinessRuleViolationException.class);
        service.updateMedicalRecord(VISIT, request("x", "y", null, null, EXAM_DAY.plusDays(30)));

        verify(records).saveAndFlush(any(MedicalRecord.class));
    }

    @Test
    void examinationDayIsTheVietnamDayOfTheCheckIn() {
        // 17:30Z = 00:30 ngày 22/10 giờ Việt Nam: ngày khám là 22/10, không phải 21/10 (UTC).
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS, Instant.parse("2026-10-21T17:30:00Z"));
        doReturn(Optional.empty()).when(records).findById(VISIT);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT,
                request("x", "y", null, null, LocalDate.of(2026, 10, 22)))).isInstanceOf(BusinessRuleViolationException.class);
        service.updateMedicalRecord(VISIT, request("x", "y", null, null, LocalDate.of(2026, 10, 23)));

        verify(records).saveAndFlush(any(MedicalRecord.class));
    }

    @Test
    void anUnchangedFollowUpDateIsNotCheckedAgainAfterTheConfigShrinks() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(recordWith("khám", "dx", EXAM_DAY.plusDays(100)))).when(records).findById(VISIT);
        doReturn(30).when(config).getInt(ConfigKey.MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS);

        service.updateMedicalRecord(VISIT, request("khám mới", "dx", null, null, EXAM_DAY.plusDays(100)));

        verify(records).saveAndFlush(any(MedicalRecord.class));
        verify(config, never()).getInt(any());
    }

    @Test
    void aChangedFollowUpDateIsCheckedAgainstTheCurrentConfig() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(recordWith("khám", "dx", EXAM_DAY.plusDays(100)))).when(records).findById(VISIT);
        doReturn(30).when(config).getInt(ConfigKey.MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS);

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT,
                request("khám", "dx", null, null, EXAM_DAY.plusDays(101)))).isInstanceOf(BusinessRuleViolationException.class);

        verify(records, never()).saveAndFlush(any());
    }

    @Test
    void clearingTheFollowUpDateNeedsNoRangeCheck() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(recordWith("khám", "dx", EXAM_DAY.plusDays(100)))).when(records).findById(VISIT);

        service.updateMedicalRecord(VISIT, request("khám", "dx", null, null, null));

        verify(config, never()).getInt(any());
        verify(records).saveAndFlush(any(MedicalRecord.class));
    }

    @Test
    void auditFailureAfterTheWriteIsNotCaught() {
        stubLoaded(VET_A, VisitStatus.IN_PROGRESS);
        doReturn(Optional.empty()).when(records).findById(VISIT);
        doThrow(new IllegalStateException("audit")).when(audit).record(any());

        assertThatThrownBy(() -> service.updateMedicalRecord(VISIT, request("x", "y", null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit");
    }

    // ---------------------------------------------------------------- helpers

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private void asVet(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("VET").when(principal).role();
    }

    private void asCaretaker(long accountId) {
        doReturn(accountId).when(principal).accountId();
        doReturn("CARETAKER").when(principal).role();
    }

    private void summary(long branchId, ServiceGroup group, VisitStatus status) {
        doReturn(Optional.of(new SummaryRow(VISIT, branchId, 88L, PET, group, status, VET_A))).when(visits)
                .findSummary(VISIT);
    }

    private Visit stubLoaded(Long assigneeId, VisitStatus status) {
        return stubLoaded(assigneeId, status, CHECKED_IN);
    }

    private Visit stubLoaded(Long assigneeId, VisitStatus status, Instant checkedInAt) {
        Visit visit = Visit.checkIn("TN-2610-" + VISIT, BRANCH, 88L, PET, null, ServiceGroup.MEDICAL,
                PriorityClass.WALK_IN, checkedInAt, 70L, checkedInAt);
        ReflectionTestUtils.setField(visit, "id", VISIT);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", status);
        summary(BRANCH, ServiceGroup.MEDICAL, status);
        doReturn(Optional.of(VISIT)).when(visits).lockById(VISIT);
        doReturn(Optional.of(visit)).when(visits).findById(VISIT);
        return visit;
    }

    private static MedicalRecord recordWith(String examination, String diagnosis, LocalDate followUp) {
        MedicalRecord record = MedicalRecord.of(VISIT);
        record.rewrite(examination, diagnosis, null, null, followUp, VET_A);
        return record;
    }

    private static UpdateMedicalRecordRequest request(String examination, String diagnosis, String plan, String note,
            LocalDate followUp) {
        return new UpdateMedicalRecordRequest(examination, diagnosis, plan, note, followUp);
    }
}
