package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VaccinationQueryApi.BoardingVaccineGap;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.LatestDose;

/**
 * {@link VaccinationQueryService} (docs/adr/0072): khoảng trống mũi tiêm bắt buộc khi lưu trú (BR-LT-05) và kiểm loại
 * vaccine đã dùng (BR-SP-07). Hành vi trên DB thật ở {@code VaccinationQueryApiIT}.
 */
class VaccinationQueryServiceTest {

    private static final long PET = 12L;
    private static final LocalDate ON = LocalDate.of(2026, 11, 10);

    private final VaccinationRepository vaccinations = mock(VaccinationRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final VaccinationQueryService service = new VaccinationQueryService(vaccinations, pets, catalog);

    private void petIs(Species species) {
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mực", species, null, null, null))).when(pets).findPet(PET);
    }

    private static LatestDose dose(long typeId, LocalDate administeredOn, LocalDate nextDue) {
        LatestDose dose = mock(LatestDose.class);
        doReturn(typeId).when(dose).getVaccineTypeId();
        doReturn(administeredOn).when(dose).getAdministeredOn();
        doReturn(nextDue).when(dose).getNextDueDate();
        return dose;
    }

    @Test
    void aSpeciesWithoutRequiredTypesHasNoGapAndNeverReadsTheDoses() {
        petIs(Species.CAT);
        doReturn(List.of()).when(catalog).findBoardingRequiredVaccineTypeIds(Species.CAT);

        assertThat(service.findBoardingVaccineGaps(PET, ON)).isEmpty();

        verifyNoInteractions(vaccinations);
    }

    @Test
    void aRequiredTypeNeverGivenIsAGapWithNullDates() {
        petIs(Species.DOG);
        doReturn(List.of(5L)).when(catalog).findBoardingRequiredVaccineTypeIds(Species.DOG);
        doReturn(List.of()).when(vaccinations).findLatestDoses(PET, List.of(5L));

        assertThat(service.findBoardingVaccineGaps(PET, ON)).containsExactly(new BoardingVaccineGap(5L, null, null));
    }

    @Test
    void aDoseDueBeforeTheDateIsAGapCarryingBothDates() {
        petIs(Species.DOG);
        doReturn(List.of(5L)).when(catalog).findBoardingRequiredVaccineTypeIds(Species.DOG);
        LocalDate given = LocalDate.of(2025, 11, 1);
        doReturn(List.of(dose(5L, given, ON.minusDays(1)))).when(vaccinations).findLatestDoses(PET, List.of(5L));

        assertThat(service.findBoardingVaccineGaps(PET, ON))
                .containsExactly(new BoardingVaccineGap(5L, given, ON.minusDays(1)));
    }

    @Test
    void aDoseDueExactlyOnTheDateOrLaterIsNotAGap() {
        petIs(Species.DOG);
        doReturn(List.of(5L, 6L)).when(catalog).findBoardingRequiredVaccineTypeIds(Species.DOG);
        doReturn(List.of(dose(5L, ON.minusDays(365), ON), dose(6L, ON.minusDays(10), ON.plusDays(355)))).when(vaccinations)
                .findLatestDoses(PET, List.of(5L, 6L));

        assertThat(service.findBoardingVaccineGaps(PET, ON)).isEmpty();
    }

    @Test
    void gapsComeSortedByTypeIdWhateverTheOrderOfTheCatalogAndOfTheRows() {
        petIs(Species.DOG);
        doReturn(List.of(9L, 5L, 7L)).when(catalog).findBoardingRequiredVaccineTypeIds(Species.DOG);
        doReturn(List.of(dose(7L, ON.minusDays(400), ON.minusDays(35)))).when(vaccinations)
                .findLatestDoses(PET, List.of(9L, 5L, 7L));

        assertThat(service.findBoardingVaccineGaps(PET, ON)).extracting(BoardingVaccineGap::vaccineTypeId)
                .containsExactly(5L, 7L, 9L);
    }

    @Test
    void aMissingPetIsCorruptCallerDataAndNothingElseIsRead() {
        doReturn(Optional.empty()).when(pets).findPet(PET);

        assertThatThrownBy(() -> service.findBoardingVaccineGaps(PET, ON)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(catalog, vaccinations);
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> service.findBoardingVaccineGaps(null, ON)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.findBoardingVaccineGaps(PET, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.existsByVaccineType(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(pets, catalog, vaccinations);
    }

    @Test
    void existsByVaccineTypeAsksTheRepository() {
        doReturn(true).when(vaccinations).existsByVaccineTypeId(5L);
        doReturn(false).when(vaccinations).existsByVaccineTypeId(6L);

        assertThat(service.existsByVaccineType(5L)).isTrue();
        assertThat(service.existsByVaccineType(6L)).isFalse();
        verify(vaccinations).existsByVaccineTypeId(5L);
        verify(pets, org.mockito.Mockito.never()).findPet(any());
    }

    @Test
    void theServiceJoinsTheCallersTransactionReadOnlyAndNeverRequiresOrSuspendsOne() {
        Transactional tx = VaccinationQueryService.class.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
        assertThat(tx.noRollbackFor()).isEmpty();
    }
}
