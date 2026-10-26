package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

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

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.dto.RecordVaccinationRequest;
import com.petcare.module.visit.dto.RecordVaccinationResultResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Vaccination;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.mapper.VaccinationOptionsMapper;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.LockedDose;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VaccinationRecordService} (visit-v1 #19, docs/adr/0074): thứ tự khóa và guard (guard sai thì không ghi gì và
 * không gọi bước sau), giá trị chụp, thứ tự 6 lời gọi ghi, mũi cũ / Care Task, cảnh báo chưa đủ tuổi. Xóa mũi (#20,
 * docs/adr/0075): thứ tự khóa, guard, thứ tự ghi, tính lại mũi cũ, ranh giới transaction. Hành vi trên DB thật ở
 * {@code VaccinationRecordIT}, {@code VaccinationDeleteIT}.
 */
class VaccinationRecordServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    private static final long TYPE = 5L;
    private static final long PROTOCOL = 102L;
    private static final long PRODUCT = 81L;
    private static final long LOT = 7001L;
    private static final long LINE = 9102L;
    private static final long NEW_ID = 701L;
    /** Mũi bị xóa (#20); lô và dòng Order khác với của #19 để nối nhầm nguồn thì test đỏ. */
    private static final long DOSE = 702L;
    private static final long DOSE_LOT = 7003L;
    private static final long DOSE_LINE = 9103L;
    /** 2026-10-25 10:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final VaccinationRepository vaccinations = mock(VaccinationRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final StockQueryApi stockQuery = mock(StockQueryApi.class);
    private final StockApi stock = mock(StockApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final CareTaskApi careTasks = mock(CareTaskApi.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));
    private final VaccinationRecordService service = new VaccinationRecordService(scope, visits, vaccinations, pets,
            catalog, stockQuery, stock, visitOrders, careTasks, Mappers.getMapper(MedicalRecordMapper.class),
            Mappers.getMapper(VaccinationOptionsMapper.class), clock);
    private final RecordVaccinationRequest request = new RecordVaccinationRequest(TYPE, PROTOCOL, PRODUCT);

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn("VET").when(principal).role();
        summary(BRANCH, ServiceGroup.MEDICAL);
        pet(null, Species.DOG);
        loaded(VET, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "Dại", Species.DOG, true))).when(catalog).findVaccineType(TYPE);
        doReturn(Optional.of(dose(PROTOCOL, TYPE, Species.DOG, true, 2, 21, 6))).when(catalog).findProtocolDose(PROTOCOL);
        doReturn(Optional.of(product(PRODUCT, ProductType.VACCINE, TYPE, true))).when(catalog).findProductForShare(PRODUCT);
        doReturn(3).when(stockQuery).availableQuantity(BRANCH, PRODUCT);
        doReturn(new LineRef(500L, LINE)).when(visitOrders).addVaccineLine(VISIT, PRODUCT, VET);
        doReturn(LOT).when(stock).lockFefoLot(BRANCH, PRODUCT, 1);
        doAnswer(invocation -> {
            Vaccination row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", NEW_ID);
            return row;
        }).when(vaccinations).saveAndFlush(any(Vaccination.class));
        doReturn(List.of()).when(vaccinations).findUnsupersededIds(PET, TYPE, NEW_ID);
    }

    // ================================================================ guard trước khóa

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachAnyRead(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, vaccinations, pets, catalog, stockQuery, stock, visitOrders, careTasks);
    }

    @Test
    void missingVisitIs404BeforeTheBranchCheck() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, vaccinations, catalog, stockQuery, stock, visitOrders, careTasks);
    }

    @Test
    void otherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, vaccinations, catalog, stockQuery, stock, visitOrders, careTasks);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void groomingVisitIs404BeforeAnyLock() {
        summary(BRANCH, ServiceGroup.GROOMING);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt khám / tiêm");

        verifyNoInteractions(pets, vaccinations, catalog, stockQuery, stock, visitOrders, careTasks);
        verify(visits, never()).lockById(anyLong());
    }

    // ================================================================ khóa và guard dưới khóa

    @Test
    void locksPetThenAdvisoryThenVisitBeforeReadingTheCatalog() {
        service.record(VISIT, request);

        InOrder order = inOrder(pets, vaccinations, visits, catalog);
        order.verify(pets).findPetForShare(PET);
        order.verify(vaccinations).acquirePetDoseLock(VaccinationRecordService.PET_DOSE_LOCK_NAMESPACE,
                VaccinationRecordService.lockKey(PET));
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(catalog).findVaccineType(TYPE);
    }

    @Test
    void theAdvisoryNamespaceIs1004AndKeyFollowsTheAppointmentFormula() {
        assertThat(VaccinationRecordService.PET_DOSE_LOCK_NAMESPACE).isEqualTo(1004);
        assertThat(VaccinationRecordService.lockKey(12L)).isEqualTo(12);
        assertThat(VaccinationRecordService.lockKey(Integer.MAX_VALUE + 5L)).isEqualTo(5);
    }

    @Test
    void aMissingPetIsCorruptData() {
        doReturn(Optional.empty()).when(pets).findPetForShare(PET);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(IllegalStateException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(visitOrders, stock, careTasks);
    }

    @Test
    void notTheAssigneeIs403UnderTheLockAndNothingElseIsRead() {
        loaded(46L, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoWrite();
        verifyNoInteractions(catalog, stockQuery);
    }

    @Test
    void anUnassignedVisitIs403() {
        loaded(null, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoWrite();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void aVisitThatIsNotInProgressIs409(VisitStatus status) {
        loaded(VET, status);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(InvalidStateTransitionException.class);

        verifyNoWrite();
        verifyNoInteractions(catalog, stockQuery);
    }

    @Test
    void theAssigneeCheckComesBeforeTheStatusCheck() {
        loaded(46L, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(AccessDeniedScopeException.class);
    }

    // ================================================================ loại, mũi, sản phẩm, tồn

    @Test
    void unknownVaccineTypeIs404() {
        doReturn(Optional.empty()).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("loại vaccine");

        verifyNoWrite();
        verify(catalog, never()).findProtocolDose(anyLong());
    }

    @Test
    void aTypeOfAnotherSpeciesIsBrKb04EvenWhenItIsAlsoInactive() {
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "4 bệnh", Species.CAT, false))).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-04)");

        verifyNoWrite();
        verify(catalog, never()).findProtocolDose(anyLong());
    }

    @Test
    void aDiscontinuedTypeIsBrSp07() {
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "Dại", Species.DOG, false))).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-SP-07)");

        verifyNoWrite();
    }

    @Test
    void anUnknownDoseIsBrKb04BeforeTheProductIsLocked() {
        doReturn(Optional.empty()).when(catalog).findProtocolDose(PROTOCOL);

        assertDoseRejected();
    }

    @Test
    void aDoseOfAnotherTypeIsBrKb04() {
        doReturn(Optional.of(dose(PROTOCOL, TYPE + 1, Species.DOG, true, 2, 21, 6))).when(catalog)
                .findProtocolDose(PROTOCOL);

        assertDoseRejected();
    }

    @Test
    void aDoseOfAnotherSpeciesIsBrKb04() {
        doReturn(Optional.of(dose(PROTOCOL, TYPE, Species.CAT, true, 2, 21, 6))).when(catalog).findProtocolDose(PROTOCOL);

        assertDoseRejected();
    }

    @Test
    void anInactiveDoseIsBrKb04() {
        doReturn(Optional.of(dose(PROTOCOL, TYPE, Species.DOG, false, 2, 21, 6))).when(catalog).findProtocolDose(PROTOCOL);

        assertDoseRejected();
    }

    @Test
    void anUnknownProductIsBrKb04() {
        doReturn(Optional.empty()).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected();
    }

    @Test
    void aProductThatIsNotAVaccineIsBrKb04() {
        doReturn(Optional.of(product(PRODUCT, ProductType.DRUG, TYPE, true))).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected();
    }

    @Test
    void aVaccineOfAnotherTypeIsBrKb04() {
        doReturn(Optional.of(product(PRODUCT, ProductType.VACCINE, TYPE + 1, true))).when(catalog)
                .findProductForShare(PRODUCT);

        assertProductRejected();
    }

    @Test
    void aDiscontinuedProductIsBrKb04() {
        doReturn(Optional.of(product(PRODUCT, ProductType.VACCINE, TYPE, false))).when(catalog)
                .findProductForShare(PRODUCT);

        assertProductRejected();
    }

    @Test
    void theProductIsAlwaysReadWithAShareLock() {
        service.record(VISIT, request);

        verify(catalog).findProductForShare(PRODUCT);
        verify(catalog, never()).findProduct(anyLong());
    }

    @Test
    void noAvailableStockIsBrKb04AndWritesNothing() {
        doReturn(0).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-04)");

        verifyNoInteractions(visitOrders, stock, careTasks);
        verify(vaccinations, never()).saveAndFlush(any());
    }

    // ================================================================ ghi

    @Test
    void writesInTheContractOrderAndSnapshotsTheValues() {
        doReturn(List.of(600L, 601L)).when(vaccinations).findUnsupersededIds(PET, TYPE, NEW_ID);

        RecordVaccinationResultResponse result = service.record(VISIT, request);

        InOrder order = inOrder(visitOrders, stock, vaccinations, careTasks);
        order.verify(visitOrders).addVaccineLine(VISIT, PRODUCT, VET);
        order.verify(stock).lockFefoLot(BRANCH, PRODUCT, 1);
        order.verify(vaccinations).saveAndFlush(any(Vaccination.class));
        order.verify(stock).issueForVaccination(LOT, 1, NEW_ID, VET);
        order.verify(vaccinations).findUnsupersededIds(PET, TYPE, NEW_ID);
        order.verify(vaccinations).markSuperseded(List.of(600L, 601L), NOW);
        order.verify(careTasks).cancelForRevaccination(List.of(600L, 601L));

        ArgumentCaptor<Vaccination> saved = ArgumentCaptor.forClass(Vaccination.class);
        verify(vaccinations).saveAndFlush(saved.capture());
        Vaccination row = saved.getValue();
        assertThat(row.getPetId()).isEqualTo(PET);
        assertThat(row.getVaccineTypeId()).isEqualTo(TYPE);
        assertThat(row.getVisitId()).isEqualTo(VISIT);
        assertThat(row.getBranchId()).isEqualTo(BRANCH);
        assertThat(row.getAdministeredOn()).isEqualTo(TODAY);
        assertThat(row.getProtocolId()).isEqualTo(PROTOCOL);
        assertThat(row.getDoseNumber()).isEqualTo(2);
        assertThat(row.getProductId()).isEqualTo(PRODUCT);
        assertThat(row.getStockLotId()).isEqualTo(LOT);
        assertThat(row.getOrderLineId()).isEqualTo(LINE);
        assertThat(row.getNextDueDate()).isEqualTo(TODAY.plusDays(21));
        assertThat(row.getRecordedBy()).isEqualTo(VET);
        assertThat(row.isSuperseded()).isFalse();

        assertThat(result.warnings()).isEmpty();
        assertThat(result.vaccination().vaccinationId()).isEqualTo(NEW_ID);
        assertThat(result.vaccination().vaccineTypeName()).isEqualTo("Dại");
        assertThat(result.vaccination().productName()).isEqualTo("Rabisin");
        assertThat(result.vaccination().lotNumber()).isNull();
        assertThat(result.vaccination().superseded()).isFalse();
    }

    @Test
    void withoutOlderDosesNothingIsSupersededAndNoTaskIsTouched() {
        service.record(VISIT, request);

        verify(vaccinations, never()).markSuperseded(anyCollection(), any());
        verifyNoInteractions(careTasks);
    }

    @Test
    void aFefoFailureAfterTheOrderLinePropagatesAndSavesNothing() {
        doThrow(new BusinessRuleViolationException("BR-KO-05", "Không còn lô")).when(stock).lockFefoLot(BRANCH, PRODUCT, 1);

        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KO-05)");

        verify(vaccinations, never()).saveAndFlush(any());
        verify(stock, never()).issueForVaccination(anyLong(), anyInt(), anyLong(), anyLong());
        verifyNoInteractions(careTasks);
    }

    // ================================================================ cảnh báo

    @Test
    void underMinAgeIsAWarningOfTheChosenDoseNotABlock() {
        // sinh 2026-10-05 → 3 tuần; mũi chọn cần 6 tuần
        pet(LocalDate.of(2026, 10, 5), Species.DOG);

        RecordVaccinationResultResponse result = service.record(VISIT, request);

        assertThat(result.warnings()).containsExactly("UNDER_MIN_AGE");
        verify(vaccinations).saveAndFlush(any(Vaccination.class));
    }

    @Test
    void exactlyTheMinimumAgeHasNoWarning() {
        pet(TODAY.minusWeeks(6), Species.DOG);

        assertThat(service.record(VISIT, request).warnings()).isEmpty();
    }

    @Test
    void aPetWithoutBirthDateHasNoWarning() {
        assertThat(service.record(VISIT, request).warnings()).isEmpty();
    }

    // ================================================================ xóa mũi (#20, docs/adr/0075)

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void deleteByRolesOutsideTheContractNeverReachesAnyRead(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, vaccinations, pets, catalog, stockQuery, stock, visitOrders, careTasks);
    }

    @Test
    void deleteOfAMissingVisitIs404BeforeTheBranchCheck() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, vaccinations, stock, visitOrders, careTasks);
    }

    @Test
    void deleteInAnotherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, vaccinations, stock, visitOrders, careTasks);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void deleteInAGroomingVisitIs404BeforeAnyLock() {
        summary(BRANCH, ServiceGroup.GROOMING);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt khám / tiêm");

        verifyNoInteractions(pets, vaccinations, stock, visitOrders, careTasks);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void deleteLocksPetThenAdvisoryThenVisitThenTheDoseAndOnlyThenWrites() {
        deletable(false);

        service.delete(VISIT, DOSE);

        InOrder order = inOrder(pets, vaccinations, visits, visitOrders, stock);
        order.verify(pets).findPetForShare(PET);
        order.verify(vaccinations).acquirePetDoseLock(VaccinationRecordService.PET_DOSE_LOCK_NAMESPACE,
                VaccinationRecordService.lockKey(PET));
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(vaccinations).lockDoseInVisit(DOSE, VISIT);
        order.verify(vaccinations).deleteDose(DOSE);
        order.verify(visitOrders).removeVaccineLine(DOSE_LINE);
        order.verify(stock).revertVaccination(DOSE_LOT, 1, DOSE, VET);
        order.verify(vaccinations).restoreLatest(PET, TYPE, NOW);
    }

    @Test
    void deleteNotByTheAssigneeIs403UnderTheLockAndWritesNothing() {
        deletable(false);
        loaded(46L, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoDeleteWrite();
        verify(vaccinations, never()).lockDoseInVisit(anyLong(), anyLong());
    }

    @Test
    void deleteInAnUnassignedVisitIs403() {
        deletable(false);
        loaded(null, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoDeleteWrite();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void deleteInAVisitThatIsNotInProgressIs409(VisitStatus status) {
        deletable(false);
        loaded(VET, status);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(InvalidStateTransitionException.class);

        verifyNoDeleteWrite();
        verify(vaccinations, never()).lockDoseInVisit(anyLong(), anyLong());
    }

    @Test
    void deleteChecksTheAssigneeBeforeTheStatus() {
        deletable(false);
        loaded(46L, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void deleteOfADoseThatIsNotInThisVisitIs404AndWritesNothing() {
        doReturn(Optional.empty()).when(vaccinations).lockDoseInVisit(DOSE, VISIT);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("mũi tiêm");

        verifyNoDeleteWrite();
    }

    @Test
    void deleteOfAMissingPetIsCorruptData() {
        doReturn(Optional.empty()).when(pets).findPetForShare(PET);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(IllegalStateException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoDeleteWrite();
    }

    @Test
    void deletingTheLatestDoseRestoresTheLatestRemainingOne() {
        deletable(false);

        service.delete(VISIT, DOSE);

        verify(vaccinations).restoreLatest(PET, TYPE, NOW);
    }

    @Test
    void deletingASupersededDoseChangesNoOtherDose() {
        deletable(true);

        service.delete(VISIT, DOSE);

        verify(vaccinations).deleteDose(DOSE);
        verify(visitOrders).removeVaccineLine(DOSE_LINE);
        verify(stock).revertVaccination(DOSE_LOT, 1, DOSE, VET);
        verify(vaccinations, never()).restoreLatest(anyLong(), anyLong(), any());
    }

    @Test
    void deleteDoesNotTouchCareTasksOrLoadTheDoseEntity() {
        deletable(false);

        service.delete(VISIT, DOSE);

        verifyNoInteractions(careTasks, catalog, stockQuery);
        verify(vaccinations, never()).findById(any());
        verify(vaccinations, never()).saveAndFlush(any());
    }

    @Test
    void aDoseThatLockedButCannotBeDeletedIsCorruptDataAndNothingElseIsWritten() {
        deletable(false);
        doReturn(0).when(vaccinations).deleteDose(DOSE);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visitOrders, stock, careTasks);
        verify(vaccinations, never()).restoreLatest(anyLong(), anyLong(), any());
    }

    @Test
    void aFailureRemovingTheOrderLineStopsBeforeTheStockAndTheRestore() {
        deletable(false);
        doThrow(new IllegalStateException("line")).when(visitOrders).removeVaccineLine(DOSE_LINE);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(stock, careTasks);
        verify(vaccinations, never()).restoreLatest(anyLong(), anyLong(), any());
    }

    @Test
    void aFailureRevertingTheLotStopsBeforeTheRestore() {
        deletable(false);
        doThrow(new ResourceNotFoundException("lô hàng", DOSE_LOT)).when(stock).revertVaccination(DOSE_LOT, 1, DOSE, VET);

        assertThatThrownBy(() -> service.delete(VISIT, DOSE)).isInstanceOf(ResourceNotFoundException.class);

        verify(vaccinations, never()).restoreLatest(anyLong(), anyLong(), any());
        verifyNoInteractions(careTasks);
    }

    /** ADR-0075 T5: READ COMMITTED mặc định, ghi, không {@code noRollbackFor} — các lần đọc sau khóa phải thấy dữ liệu mới. */
    @Test
    void deleteIsOneReadWriteTransactionWithTheDefaultIsolationAndNoNoRollbackFor() throws Exception {
        Transactional tx = VaccinationRecordService.class.getMethod("delete", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    /** ADR-0074: cùng cam kết giao dịch như {@code delete} — một giao dịch ghi, READ COMMITTED, không {@code noRollbackFor}. */
    @Test
    void recordIsOneReadWriteTransactionWithTheDefaultIsolationAndNoNoRollbackFor() throws Exception {
        Transactional tx = VaccinationRecordService.class.getMethod("record", Long.class, RecordVaccinationRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    /** Mũi {@link #DOSE} của lượt, khóa được và xóa được (1 dòng); {@code superseded} là cờ lúc khóa. */
    private void deletable(boolean superseded) {
        doReturn(Optional.of(new LockedDose() {
            @Override
            public Long getId() {
                return DOSE;
            }

            @Override
            public Long getPetId() {
                return PET;
            }

            @Override
            public Long getVaccineTypeId() {
                return TYPE;
            }

            @Override
            public Long getStockLotId() {
                return DOSE_LOT;
            }

            @Override
            public Long getOrderLineId() {
                return DOSE_LINE;
            }

            @Override
            public Boolean getSuperseded() {
                return superseded;
            }
        })).when(vaccinations).lockDoseInVisit(DOSE, VISIT);
        doReturn(1).when(vaccinations).deleteDose(DOSE);
    }

    /** Guard sai của #20: không xóa mũi, không đụng Order, kho, Care Task, không tính lại mũi cũ. */
    private void verifyNoDeleteWrite() {
        verifyNoInteractions(visitOrders, stock, careTasks);
        verify(vaccinations, never()).deleteDose(anyLong());
        verify(vaccinations, never()).restoreLatest(anyLong(), anyLong(), any());
    }

    private void assertDoseRejected() {
        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-04)");

        verify(catalog, never()).findProductForShare(anyLong());
        verifyNoInteractions(stockQuery, visitOrders, stock, careTasks);
        verify(vaccinations, never()).saveAndFlush(any());
    }

    private void assertProductRejected() {
        assertThatThrownBy(() -> service.record(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-04)");

        verifyNoInteractions(stockQuery, visitOrders, stock, careTasks);
        verify(vaccinations, never()).saveAndFlush(any());
    }

    /** Guard sai: không có lệnh ghi nào (Order, kho, mũi, Care Task). */
    private void verifyNoWrite() {
        verifyNoInteractions(visitOrders, stock, careTasks);
        verify(vaccinations, never()).saveAndFlush(any());
        verify(vaccinations, never()).markSuperseded(anyCollection(), any());
    }

    private void summary(long branchId, ServiceGroup group) {
        doReturn(Optional.of(new SummaryRow(VISIT, branchId, 88L, PET, group, VisitStatus.IN_PROGRESS, VET)))
                .when(visits).findSummary(VISIT);
    }

    private void pet(LocalDate birthDate, Species species) {
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mực", species, birthDate, null, null))).when(pets)
                .findPetForShare(PET);
    }

    private void loaded(Long assigneeId, VisitStatus status) {
        Instant checkedIn = Instant.parse("2026-10-25T02:00:00Z");
        Visit visit = Visit.checkIn("TN-2610-" + VISIT, BRANCH, 88L, PET, null, ServiceGroup.MEDICAL,
                PriorityClass.WALK_IN, checkedIn, 70L, checkedIn);
        ReflectionTestUtils.setField(visit, "id", VISIT);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", status);
        doReturn(Optional.of(VISIT)).when(visits).lockById(VISIT);
        doReturn(Optional.of(visit)).when(visits).findById(VISIT);
    }

    private static ProtocolDose dose(long id, long typeId, Species species, boolean active, int doseNumber,
            int intervalDays, int minAgeWeeks) {
        return new ProtocolDose(id, species, typeId, doseNumber, intervalDays, minAgeWeeks, false, active);
    }

    private static ProductInfo product(long id, ProductType type, long typeId, boolean active) {
        return new ProductInfo(id, "Rabisin", type, false, true, typeId, 150_000, active);
    }
}
