package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.Appetite;
import com.petcare.module.inpatient.mapper.AdmissionObservationMapper;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmissionSummaryResponse;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.mapper.AdmissionOrderMapper;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * {@link AdmissionReadService} (inpatient-v1 #2, #3; docs/adr/0149 mục 19): danh sách luôn lọc theo chi nhánh của người
 * gọi ({@code resolve(null)}), {@code status} mặc định {@code ADMITTED}, trang được kẹp, dữ liệu ghép theo lô (3 câu cố
 * định), mã chuồng đúng nguồn theo đường nhập, một {@code now} cho cả trang; chi tiết 404 → 403; thiếu dữ liệu ghép là
 * dữ liệu hỏng.
 */
class AdmissionReadServiceTest {

    private static final long BRANCH = 3L;
    /** 2026-11-23 10:00 giờ VN; ca nhập 2026-11-22 → 2 ngày tính tiền. */
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final Instant ADMITTED_AT = Instant.parse("2026-11-22T02:00:00Z");

    private final BranchScope scope = mock(BranchScope.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final KennelApi kennels = mock(KennelApi.class);
    private final AdmissionOrderRepository admissionOrders = mock(AdmissionOrderRepository.class);
    private final AdmissionObservationRepository observations = mock(AdmissionObservationRepository.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final AdmissionReadService service = new AdmissionReadService(scope, admissions, pets, catalog, kennels,
            Mappers.getMapper(AdmissionMapper.class), admissionOrders, Mappers.getMapper(AdmissionOrderMapper.class),
            observations, Mappers.getMapper(AdmissionObservationMapper.class), staff,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private final Admission fromVisit = admission(51L, 12L, null, 17L, 200_000L);
    private final Admission fromBoarding = admission(52L, 13L, 301L, null, 300_000L);

    @BeforeEach
    void setUp() {
        doReturn(BRANCH).when(scope).resolve(null);
        doReturn(new PageImpl<>(List.of(fromVisit, fromBoarding), PageRequest.of(0, 20), 2)).when(admissions)
                .findPageByBranchAndStatus(eq(BRANCH), any(), any());
        doReturn(List.of(pet(13L, "Bơ"), pet(12L, "Mochi"))).when(pets).findPets(any());
        doReturn(Map.of(17L, "D-01")).when(kennels).findCodesByKennelIds(any());
        doReturn(Map.of(301L, "C-03")).when(kennels).findCodesByBookingIds(any());
    }

    @Test
    void listDefaultsToAdmittedInTheCallersBranch() {
        service.listAdmissions(null, 0, 20);

        verify(scope).resolve(null);
        verify(admissions).findPageByBranchAndStatus(eq(BRANCH), eq(AdmissionStatus.ADMITTED), any());
    }

    @Test
    void listPassesAnExplicitStatus() {
        service.listAdmissions(AdmissionStatus.DISCHARGED, 0, 20);

        verify(admissions).findPageByBranchAndStatus(eq(BRANCH), eq(AdmissionStatus.DISCHARGED), any());
    }

    @Test
    void listIsRejectedForAnOutOfScopeCallerBeforeReading() {
        doThrow(new AccessDeniedScopeException("branch:null", "owner")).when(scope).resolve(null);

        assertThatThrownBy(() -> service.listAdmissions(null, 0, 20)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(admissions, pets, kennels);
    }

    @Test
    void listMapsPetNamesByIdAndKennelCodesBySourceWithOneNow() {
        PageResponse<AdmissionSummaryResponse> page = service.listAdmissions(null, 0, 20);

        assertThat(page.content()).extracting(AdmissionSummaryResponse::admissionId).containsExactly(51L, 52L);
        AdmissionSummaryResponse first = page.content().get(0);
        AdmissionSummaryResponse second = page.content().get(1);
        assertThat(first.petName()).isEqualTo("Mochi");
        assertThat(first.kennelCode()).isEqualTo("D-01");
        assertThat(second.petName()).isEqualTo("Bơ");
        assertThat(second.kennelCode()).isEqualTo("C-03");
        assertThat(first.billedDays()).isEqualTo(2);
        assertThat(first.estimatedAmount()).isEqualTo(400_000L);
        assertThat(second.billedDays()).isEqualTo(2);
        assertThat(second.estimatedAmount()).isEqualTo(600_000L);
        verify(pets).findPets(List.of(12L, 13L));
        verify(kennels).findCodesByKennelIds(List.of(17L));
        verify(kennels).findCodesByBookingIds(List.of(301L));
    }

    @Test
    void listReadsThePageBeforeTheBatchesAndRunsEachBatchOnce() {
        service.listAdmissions(null, 0, 20);

        InOrder order = inOrder(scope, admissions, pets, kennels);
        order.verify(scope).resolve(null);
        order.verify(admissions).findPageByBranchAndStatus(eq(BRANCH), any(), any());
        order.verify(pets).findPets(any());
        order.verify(kennels).findCodesByKennelIds(any());
        order.verify(kennels).findCodesByBookingIds(any());
        verifyNoInteractions(catalog);
    }

    @Test
    void aMissingPetInTheBatchIsCorruptData() {
        doReturn(List.of(pet(13L, "Bơ"))).when(pets).findPets(any());

        assertThatThrownBy(() -> service.listAdmissions(null, 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingKennelCodeInTheBatchIsCorruptData() {
        doReturn(Map.of()).when(kennels).findCodesByBookingIds(any());

        assertThatThrownBy(() -> service.listAdmissions(null, 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20, 0, 20", "0, 0, 0, 1", "0, -5, 0, 1", "3, 500, 3, 100",
            "2147483647, 100, 21474836, 100", "2147483647, 1, 2147483647, 1"})
    void pageAndSizeAreClampedSoTheOffsetNeverOverflows(int page, int size, int expectedPage, int expectedSize) {
        service.listAdmissions(null, page, size);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(admissions).findPageByBranchAndStatus(eq(BRANCH), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(expectedSize);
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
    }

    // ================================================================ #3

    @Test
    void detailIs404WhenMissing() {
        doReturn(Optional.empty()).when(admissions).findById(99L);

        assertThatThrownBy(() -> service.getAdmission(99L)).isInstanceOf(ResourceNotFoundException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, catalog);
    }

    @Test
    void detailOfAnotherBranchIs403BeforeEnriching() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.getAdmission(51L)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, catalog);
    }

    @Test
    void detailMapsTheCurrentNamesAndTheFrozenPrice() {
        doReturn(Optional.of(fromBoarding)).when(admissions).findById(52L);
        doReturn(Optional.of(pet(13L, "Bơ"))).when(pets).findPet(13L);
        doReturn(Optional.of(new ServiceInfo(41L, "Nội trú (giá mới)", ServiceGroup.MEDICAL, MedicalType.INPATIENT,
                999_000L, true))).when(catalog).findService(41L);

        AdmissionResponse response = service.getAdmission(52L);

        assertThat(response.petName()).isEqualTo("Bơ");
        assertThat(response.serviceName()).isEqualTo("Nội trú (giá mới)");
        assertThat(response.unitPrice()).isEqualTo(300_000L);
        assertThat(response.billedDays()).isEqualTo(2);
        assertThat(response.estimatedAmount()).isEqualTo(600_000L);
        assertThat(response.boardingBookingId()).isEqualTo(301L);
        assertThat(response.kennelId()).isNull();
    }

    @Test
    void detailWithAMissingServiceIsCorruptData() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doReturn(Optional.of(pet(12L, "Mochi"))).when(pets).findPet(12L);
        doReturn(Optional.empty()).when(catalog).findService(41L);

        assertThatThrownBy(() -> service.getAdmission(51L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAreReadOnlyRepeatableRead() throws Exception {
        for (Method method : List.of(
                AdmissionReadService.class.getMethod("listAdmissions", AdmissionStatus.class, int.class, int.class),
                AdmissionReadService.class.getMethod("getAdmission", Long.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx.readOnly()).as(method.getName()).isTrue();
            assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }


    // ================================================================ listOrders (inpatient-v1 #6)

    @Test
    void listOrdersChecksTheBranchOfTheAdmissionThenReadsInTheContractOrder() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        AdmissionOrder first = orderRow(88L, AdmissionOrderType.FEEDING, null);
        doReturn(List.of(first)).when(admissionOrders).findByAdmissionIdOrderByScheduledAtAscIdAsc(51L);

        List<AdmissionOrderResponse> result = service.listOrders(51L, null);

        InOrder inOrder = inOrder(admissions, scope, admissionOrders);
        inOrder.verify(admissions).findById(51L);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(admissionOrders).findByAdmissionIdOrderByScheduledAtAscIdAsc(51L);
        assertThat(result).extracting(AdmissionOrderResponse::admissionOrderId).containsExactly(88L);
    }

    @Test
    void listOrdersWithAStatusUsesTheFilteredQuery() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doReturn(List.of()).when(admissionOrders).findByAdmissionIdAndStatusOrderByScheduledAtAscIdAsc(51L,
                AdmissionOrderStatus.ORDERED);

        assertThat(service.listOrders(51L, AdmissionOrderStatus.ORDERED)).isEmpty();

        verify(admissionOrders, never()).findByAdmissionIdOrderByScheduledAtAscIdAsc(any());
    }

    @Test
    void listOrdersOfAMissingAdmissionIs404AndAnotherBranchIs403WithoutReadingOrders() {
        doReturn(Optional.empty()).when(admissions).findById(404L);
        assertThatThrownBy(() -> service.listOrders(404L, null)).isInstanceOf(ResourceNotFoundException.class);

        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);
        assertThatThrownBy(() -> service.listOrders(51L, null)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(admissionOrders);
    }

    @Test
    void listOrdersIsReadOnly() throws Exception {
        Method method = AdmissionReadService.class.getMethod("listOrders", Long.class, AdmissionOrderStatus.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    // ================================================================ listObservations (inpatient-v1 #10)

    @Test
    void listObservationsChecksTheBranchThenReadsNewestFirstAndResolvesNamesInOneBatch() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        AdmissionObservation newer = observationRow(302L, 45L, "Bé ăn ít");
        AdmissionObservation older = observationRow(301L, 46L, "Ổn");
        doReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 20), 2)).when(observations)
                .findByAdmissionIdOrderByCreatedAtDescIdDesc(eq(51L), any());
        doReturn(List.of(staffSummary(46L, "Lê Thị Bình"), staffSummary(45L, "Trần Văn Minh"))).when(staff)
                .findStaffByIds(any());

        PageResponse<ObservationResponse> page = service.listObservations(51L, 0, 20);

        InOrder inOrder = inOrder(admissions, scope, observations, staff);
        inOrder.verify(admissions).findById(51L);
        inOrder.verify(scope).check(BRANCH);
        inOrder.verify(observations).findByAdmissionIdOrderByCreatedAtDescIdDesc(eq(51L), any());
        inOrder.verify(staff).findStaffByIds(List.of(45L, 46L));
        assertThat(page.content()).extracting(ObservationResponse::observationId).containsExactly(302L, 301L);
        assertThat(page.content()).extracting(ObservationResponse::recordedByName)
                .containsExactly("Trần Văn Minh", "Lê Thị Bình");
        assertThat(page.totalElements()).isEqualTo(2);
        verify(staff, org.mockito.Mockito.times(1)).findStaffByIds(any());
    }

    @Test
    void listObservationsOfAMissingAdmissionIs404AndAnotherBranchIs403WithoutReadingObservations() {
        doReturn(Optional.empty()).when(admissions).findById(404L);
        assertThatThrownBy(() -> service.listObservations(404L, 0, 20)).isInstanceOf(ResourceNotFoundException.class);

        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doThrow(new AccessDeniedScopeException("branch:3", "branch:9")).when(scope).check(BRANCH);
        assertThatThrownBy(() -> service.listObservations(51L, 0, 20)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(observations, staff);
    }

    @Test
    void aMissingRecorderNameIsCorruptData() {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doReturn(new PageImpl<>(List.of(observationRow(301L, 45L, "Ổn")), PageRequest.of(0, 20), 1)).when(observations)
                .findByAdmissionIdOrderByCreatedAtDescIdDesc(eq(51L), any());
        doReturn(List.of()).when(staff).findStaffByIds(any());

        assertThatThrownBy(() -> service.listObservations(51L, 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20, 0, 20", "0, 0, 0, 1", "3, 500, 3, 100", "2147483647, 100, 21474836, 100"})
    void listObservationsClampsPageAndSize(int page, int size, int expectedPage, int expectedSize) {
        doReturn(Optional.of(fromVisit)).when(admissions).findById(51L);
        doReturn(new PageImpl<AdmissionObservation>(List.of(), PageRequest.of(0, 20), 0)).when(observations)
                .findByAdmissionIdOrderByCreatedAtDescIdDesc(eq(51L), any());

        service.listObservations(51L, page, size);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(observations).findByAdmissionIdOrderByCreatedAtDescIdDesc(eq(51L), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(expectedSize);
    }

    @Test
    void listObservationsIsReadOnlyRepeatableRead() throws Exception {
        Method method = AdmissionReadService.class.getMethod("listObservations", Long.class, int.class, int.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private static AdmissionObservation observationRow(long id, long recordedBy, String note) {
        AdmissionObservation row = AdmissionObservation.record(admission(51L, 12L, null, 17L, 200_000L), recordedBy,
                new java.math.BigDecimal("38.6"), 120, null, null, Appetite.REDUCED, note, false);
        ReflectionTestUtils.setField(row, "id", id);
        ReflectionTestUtils.setField(row, "createdAt", Instant.parse("2026-11-23T02:30:00Z"));
        return row;
    }

    private static StaffSummary staffSummary(long id, String name) {
        return new StaffSummary(id, name, Role.VET, BRANCH, true, false);
    }

    private static AdmissionOrder orderRow(long id, AdmissionOrderType type, Long itemId) {
        Admission admission = admission(51L, 12L, null, 17L, 200_000L);
        AdmissionOrder order = AdmissionOrder.order(admission, type, "Cho ăn", Instant.parse("2026-11-23T05:00:00Z"),
                itemId, 45L);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    // ================================================================ helpers

    private static Admission admission(long id, long petId, Long bookingId, Long kennelId, long price) {
        Admission admission = Admission.admit("NT-2611-000" + id, 9000L + id, petId, BRANCH, bookingId, kennelId, 41L,
                price, 45L, ADMITTED_AT);
        ReflectionTestUtils.setField(admission, "id", id);
        return admission;
    }

    private static PetSummary pet(long id, String name) {
        return new PetSummary(id, 88L, name, Species.DOG, null, null, null);
    }
}
