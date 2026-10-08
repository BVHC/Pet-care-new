package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.dto.CreateProtocolRequest;
import com.petcare.module.catalog.dto.UpdateProtocolRequest;
import com.petcare.module.catalog.dto.VaccinationProtocolResponse;
import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.catalog.entity.VaccineType;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapperImpl;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/** UC31, BR-SP-02: phác đồ tiêm chủng. BR-SP-03 không có mã riêng (ngày tái chủng đã lưu ở mũi tiêm). */
@ExtendWith(MockitoExtension.class)
class VaccinationProtocolServiceTest {

    @Mock VaccinationProtocolRepository protocols;
    @Mock VaccineTypeRepository vaccineTypes;

    VaccinationProtocolService service;

    @BeforeEach
    void setUp() {
        service = new VaccinationProtocolService(protocols, vaccineTypes, new CatalogMapperImpl());
    }

    private void dogRabies() {
        when(vaccineTypes.findById(3L)).thenReturn(Optional.of(new VaccineType("Dại", Species.DOG)));
    }

    private static CreateProtocolRequest create(Species species, int dose, int interval, int minAge) {
        return new CreateProtocolRequest(species, 3L, dose, interval, minAge, true);
    }

    private static void assertRule(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-02"));
    }

    @Test
    void createsDose() {
        dogRabies();
        when(protocols.save(any(VaccinationProtocol.class))).thenAnswer(inv -> inv.getArgument(0));

        VaccinationProtocolResponse response = service.createProtocol(create(Species.DOG, 1, 365, 12));

        assertThat(response.doseNumber()).isEqualTo(1);
        assertThat(response.intervalDays()).isEqualTo(365);
        assertThat(response.requiredForBoarding()).isTrue();
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void unknownVaccineTypeIsNotFound() {
        when(vaccineTypes.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, 365, 12)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void speciesMustMatchVaccineType() {
        dogRabies();

        assertThatThrownBy(() -> service.createProtocol(create(Species.CAT, 1, 365, 12)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
    }

    @Test
    void intervalMustBePositive() {
        dogRabies();

        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, 0, 12)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, -5, 12)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        verify(protocols, never()).save(any());
    }

    @Test
    void doseNumberAndMinAgeHaveBounds() {
        dogRabies();

        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 0, 30, 12)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 40_000, 30, 12)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, 30, -1)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, 30, 40_000)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
    }

    @Test
    void zeroMinAgeIsAllowed() {
        dogRabies();
        when(protocols.save(any(VaccinationProtocol.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.createProtocol(create(Species.DOG, 1, 30, 0)).minAgeWeeks()).isZero();
    }

    @Test
    void duplicateDoseIsRejected() {
        dogRabies();
        when(protocols.existsBySpeciesAndVaccineTypeIdAndDoseNumber(Species.DOG, 3L, 1)).thenReturn(true);

        assertThatThrownBy(() -> service.createProtocol(create(Species.DOG, 1, 365, 12)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    private VaccinationProtocol existing() {
        VaccinationProtocol protocol = new VaccinationProtocol(Species.DOG, 3L, 1, 21, 6, false);
        ReflectionTestUtils.setField(protocol, "id", 4L);
        when(protocols.findById(4L)).thenReturn(Optional.of(protocol));
        return protocol;
    }

    @Test
    void updatesOnlyGivenFields() {
        existing();
        when(protocols.saveAndFlush(any(VaccinationProtocol.class))).thenAnswer(inv -> inv.getArgument(0));

        VaccinationProtocolResponse response = service.updateProtocol(4L,
                new UpdateProtocolRequest(30, null, true, false));

        assertThat(response.intervalDays()).isEqualTo(30);
        assertThat(response.minAgeWeeks()).isEqualTo(6);
        assertThat(response.requiredForBoarding()).isTrue();
        assertThat(response.isActive()).isFalse();
        assertThat(response.doseNumber()).isEqualTo(1);
    }

    @Test
    void updateRejectsNonPositiveInterval() {
        existing();

        assertThatThrownBy(() -> service.updateProtocol(4L, new UpdateProtocolRequest(0, null, null, null)))
                .satisfies(VaccinationProtocolServiceTest::assertRule);
        verify(protocols, never()).saveAndFlush(any());
    }

    @Test
    void updateUnknownIsNotFound() {
        when(protocols.findById(4L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProtocol(4L, new UpdateProtocolRequest(null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
