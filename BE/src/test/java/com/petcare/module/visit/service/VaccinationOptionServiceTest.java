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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.VaccinationOptionsMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VaccinationOptionService} (visit-v1 #18, docs/adr/0073): thứ tự guard, guard sai thì không đọc tiếp, gợi ý mũi
 * (A18), chưa đủ tuổi, lọc sản phẩm. Hành vi trên DB thật ở {@code VaccinationOptionsIT}.
 */
class VaccinationOptionServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    private static final long TYPE = 5L;
    /** 2026-10-25 giờ Việt Nam. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final StockQueryApi stock = mock(StockQueryApi.class);
    private final VaccinationRecordReader doses = mock(VaccinationRecordReader.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private final VaccinationOptionService service = new VaccinationOptionService(scope, visits, pets, catalog, stock,
            doses, Mappers.getMapper(VaccinationOptionsMapper.class), clock);

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn("VET").when(principal).role();
        summary(BRANCH, ServiceGroup.MEDICAL, VET);
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "Dại", Species.DOG, true))).when(catalog).findVaccineType(TYPE);
        pet(null);
        doReturn(List.of(dose(101, 1, 8), dose(102, 2, 12))).when(catalog).findProtocol(Species.DOG, TYPE);
        doReturn(Optional.empty()).when(doses).latestOfType(PET, TYPE);
        doReturn(List.of()).when(catalog).findProductsByVaccineType(TYPE);
    }

    // ================================================================ guard

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachAnyRead(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, pets, catalog, stock, doses);
    }

    @Test
    void missingVisitIs404BeforeTheBranchCheck() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, catalog, stock, doses);
    }

    @Test
    void otherBranchIs403BeforeTheGroupAndTheVaccineType() {
        summary(9L, ServiceGroup.GROOMING, 99L);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, catalog, stock, doses);
    }

    @Test
    void groomingVisitIs404() {
        summary(BRANCH, ServiceGroup.GROOMING, VET);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt khám / tiêm");

        verifyNoInteractions(pets, catalog, stock, doses);
    }

    @Test
    void notTheCurrentAssigneeIs403BeforeTheVaccineType() {
        summary(BRANCH, ServiceGroup.MEDICAL, 46L);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, catalog, stock, doses);
    }

    @Test
    void anUnassignedVisitIs403() {
        summary(BRANCH, ServiceGroup.MEDICAL, null);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(catalog);
    }

    @Test
    void unknownVaccineTypeIs404() {
        doReturn(Optional.empty()).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("loại vaccine");

        verifyNoInteractions(pets, stock, doses);
    }

    @Test
    void aTypeOfAnotherSpeciesIsBrKb04EvenWhenItIsAlsoInactive() {
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "4 bệnh", Species.CAT, false))).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-04)");

        verify(catalog, never()).findProtocol(any(), anyLong());
        verifyNoInteractions(stock, doses);
    }

    @Test
    void aDiscontinuedTypeIsBrSp07() {
        doReturn(Optional.of(new VaccineTypeInfo(TYPE, "Dại", Species.DOG, false))).when(catalog).findVaccineType(TYPE);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-SP-07)");

        verify(catalog, never()).findProtocol(any(), anyLong());
        verifyNoInteractions(stock, doses);
    }

    @Test
    void aMissingPetIsCorruptData() {
        doReturn(Optional.empty()).when(pets).findPet(PET);

        assertThatThrownBy(() -> service.getOptions(VISIT, TYPE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void guardsRunInContractOrderBeforeAnyDataRead() {
        service.getOptions(VISIT, TYPE);

        InOrder order = inOrder(scope, visits, catalog, pets, doses);
        order.verify(scope).current();
        order.verify(visits).findSummary(VISIT);
        order.verify(scope).check(BRANCH);
        order.verify(catalog).findVaccineType(TYPE);
        order.verify(pets).findPet(PET);
        order.verify(catalog).findProtocol(Species.DOG, TYPE);
        order.verify(doses).latestOfType(PET, TYPE);
        order.verify(catalog).findProductsByVaccineType(TYPE);
    }

    @Test
    void anyVisitStatusIsAllowedForTheAssignee() {
        for (VisitStatus status : VisitStatus.values()) {
            doReturn(Optional.of(new SummaryRow(VISIT, BRANCH, 88L, PET, ServiceGroup.MEDICAL, status, VET)))
                    .when(visits).findSummary(VISIT);

            assertThat(service.getOptions(VISIT, TYPE).vaccineTypeId()).as(status.name()).isEqualTo(TYPE);
        }
    }

    // ================================================================ dữ liệu trả về

    @Test
    void protocolIsReturnedAsIsAndNeverDoseNoneSuggestsTheSmallestDose() {
        VaccinationOptionsResponse options = service.getOptions(VISIT, TYPE);

        assertThat(options.protocol()).extracting("protocolId", "doseNumber", "minAgeWeeks").containsExactly(
                org.assertj.core.groups.Tuple.tuple(101L, 1, 8), org.assertj.core.groups.Tuple.tuple(102L, 2, 12));
        assertThat(options.suggestedProtocolId()).isEqualTo(101L);
        assertThat(options.lastDose()).isNull();
        assertThat(options.underMinAge()).isFalse();
    }

    @Test
    void theLatestDoseDrivesTheSuggestion() {
        doReturn(Optional.of(record(1))).when(doses).latestOfType(PET, TYPE);

        VaccinationOptionsResponse options = service.getOptions(VISIT, TYPE);

        assertThat(options.lastDose().doseNumber()).isEqualTo(1);
        assertThat(options.suggestedProtocolId()).isEqualTo(102L);
    }

    @Test
    void anEmptyProtocolSuggestsNothingAndIsNeverUnderAge() {
        doReturn(List.of()).when(catalog).findProtocol(Species.DOG, TYPE);
        pet(TODAY);

        VaccinationOptionsResponse options = service.getOptions(VISIT, TYPE);

        assertThat(options.protocol()).isEmpty();
        assertThat(options.suggestedProtocolId()).isNull();
        assertThat(options.underMinAge()).isFalse();
    }

    @Test
    void underMinAgeUsesTheSuggestedDoseAndTodayFromTheClock() {
        pet(TODAY.minusWeeks(7));
        assertThat(service.getOptions(VISIT, TYPE).underMinAge()).as("7 tuần < 8").isTrue();

        pet(TODAY.minusWeeks(8));
        assertThat(service.getOptions(VISIT, TYPE).underMinAge()).as("đúng 8 tuần là đủ").isFalse();

        pet(TODAY.minusWeeks(10));
        doReturn(Optional.of(record(1))).when(doses).latestOfType(PET, TYPE);
        assertThat(service.getOptions(VISIT, TYPE).underMinAge()).as("mũi gợi ý là mũi 2, tối thiểu 12 tuần").isTrue();
    }

    @Test
    void suggestionRules() {
        List<ProtocolDose> full = List.of(dose(1, 1, 0), dose(2, 2, 0), dose(3, 3, 0));
        assertThat(VaccinationOptionService.suggest(full, null).protocolId()).isEqualTo(1L);
        assertThat(VaccinationOptionService.suggest(full, 1).protocolId()).isEqualTo(2L);
        assertThat(VaccinationOptionService.suggest(full, 3).protocolId()).as("mũi cuối → nhắc lại").isEqualTo(3L);
        List<ProtocolDose> gap = List.of(dose(1, 1, 0), dose(3, 3, 0));
        assertThat(VaccinationOptionService.suggest(gap, 2).protocolId()).as("mũi 2 đã tắt").isEqualTo(3L);
        assertThat(VaccinationOptionService.suggest(List.of(dose(1, 1, 0), dose(2, 2, 0)), 5).protocolId())
                .as("mũi đã chụp lớn hơn mọi mũi đang dùng").isEqualTo(2L);
        assertThat(VaccinationOptionService.suggest(List.of(), 1)).isNull();
        assertThat(VaccinationOptionService.suggest(List.of(dose(4, 2, 0)), null).protocolId())
                .as("mũi 1 đã tắt: mũi nhỏ nhất đang dùng").isEqualTo(4L);
    }

    @Test
    void underMinAgeRules() {
        assertThat(VaccinationOptionService.underMinAge(null, TODAY, 8)).isFalse();
        assertThat(VaccinationOptionService.underMinAge(TODAY.minusWeeks(8), TODAY, 8)).isFalse();
        assertThat(VaccinationOptionService.underMinAge(TODAY.minusWeeks(8).plusDays(1), TODAY, 8)).isTrue();
        assertThat(VaccinationOptionService.underMinAge(TODAY, TODAY, 0)).isFalse();
    }

    @Test
    void productsAreActiveInStockAtTheVisitBranchAndSortedByNameThenId() {
        doReturn(List.of(product(83, "Rabisin", true), product(81, "Nobivac", true), product(82, "Nobivac", true),
                product(84, "Cũ", false), product(85, "Hết hàng", true))).when(catalog).findProductsByVaccineType(TYPE);
        doReturn(Map.of(83L, 5, 81L, 2, 82L, 7, 85L, 0)).when(stock).availableQuantities(BRANCH,
                List.of(83L, 81L, 82L, 85L));

        VaccinationOptionsResponse options = service.getOptions(VISIT, TYPE);

        assertThat(options.products()).extracting("productId", "name", "availableQuantity").containsExactly(
                org.assertj.core.groups.Tuple.tuple(81L, "Nobivac", 2),
                org.assertj.core.groups.Tuple.tuple(82L, "Nobivac", 7),
                org.assertj.core.groups.Tuple.tuple(83L, "Rabisin", 5));
    }

    @Test
    void noActiveProductSkipsTheStockQuery() {
        doReturn(List.of(product(84, "Cũ", false))).when(catalog).findProductsByVaccineType(TYPE);

        assertThat(service.getOptions(VISIT, TYPE).products()).isEmpty();

        verifyNoInteractions(stock);
    }

    // ================================================================ helpers

    private void summary(long branchId, ServiceGroup group, Long assigneeId) {
        doReturn(Optional.of(new SummaryRow(VISIT, branchId, 88L, PET, group, VisitStatus.IN_PROGRESS, assigneeId)))
                .when(visits).findSummary(VISIT);
    }

    private void pet(LocalDate birthDate) {
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mực", Species.DOG, birthDate, null, null))).when(pets).findPet(PET);
    }

    private static ProtocolDose dose(long id, int doseNumber, int minAgeWeeks) {
        return new ProtocolDose(id, Species.DOG, TYPE, doseNumber, 21, minAgeWeeks, false, true);
    }

    private static ProductInfo product(long id, String name, boolean active) {
        return new ProductInfo(id, name, ProductType.VACCINE, false, true, TYPE, 150_000, active);
    }

    private static VaccinationRecordResponse record(int doseNumber) {
        return new VaccinationRecordResponse(701L, 8000L, 9L, TYPE, "Dại", 999L, doseNumber, 81L, "Rabisin", null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 22), false, 46L, 9102L);
    }
}
