package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.dto.UpdateVaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccineTypeResponse;
import com.petcare.module.catalog.entity.VaccineType;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapperImpl;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/** UC31, BR-SP-07: loại vaccine đã có phác đồ, sản phẩm hoặc mũi tiêm thì không xóa, chỉ ngừng sử dụng. */
@ExtendWith(MockitoExtension.class)
class VaccineTypeServiceTest {

    @Mock VaccineTypeRepository vaccineTypes;
    @Mock VaccinationProtocolRepository protocols;
    @Mock ProductRepository products;
    @Mock VaccinationQueryApi vaccinations;

    VaccineTypeService service;

    @BeforeEach
    void setUp() {
        service = new VaccineTypeService(vaccineTypes, protocols, products, vaccinations, new CatalogMapperImpl());
    }

    private VaccineType existing() {
        VaccineType type = new VaccineType("Dại", Species.DOG);
        ReflectionTestUtils.setField(type, "id", 3L);
        when(vaccineTypes.findById(3L)).thenReturn(Optional.of(type));
        return type;
    }

    @Test
    void createsAndTrimsName() {
        when(vaccineTypes.save(any(VaccineType.class))).thenAnswer(inv -> inv.getArgument(0));

        VaccineTypeResponse response = service.createVaccineType(new VaccineTypeRequest(" Dại ", Species.DOG));

        assertThat(response.name()).isEqualTo("Dại");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void duplicateNameInSameSpeciesIsRejected() {
        when(vaccineTypes.existsByNameAndSpecies("Dại", Species.DOG)).thenReturn(true);

        assertThatThrownBy(() -> service.createVaccineType(new VaccineTypeRequest("Dại", Species.DOG)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    @Test
    void renameChecksDuplicateInOwnSpecies() {
        existing();
        when(vaccineTypes.existsByNameAndSpeciesAndIdNot("5 bệnh", Species.DOG, 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateVaccineType(3L, new UpdateVaccineTypeRequest("5 bệnh", null)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    @Test
    void deactivates() {
        existing();
        when(vaccineTypes.saveAndFlush(any(VaccineType.class))).thenAnswer(inv -> inv.getArgument(0));

        VaccineTypeResponse response = service.updateVaccineType(3L, new UpdateVaccineTypeRequest(null, false));

        assertThat(response.isActive()).isFalse();
    }

    private static void assertRule(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo("BR-SP-07"));
    }

    @Test
    void deleteBlockedByProtocol() {
        existing();
        when(protocols.existsByVaccineTypeId(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteVaccineType(3L)).satisfies(VaccineTypeServiceTest::assertRule);
        verify(vaccineTypes, never()).delete(any(VaccineType.class));
        verifyNoInteractions(vaccinations);
    }

    @Test
    void deleteBlockedByProduct() {
        existing();
        when(protocols.existsByVaccineTypeId(3L)).thenReturn(false);
        when(products.existsByVaccineTypeId(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteVaccineType(3L)).satisfies(VaccineTypeServiceTest::assertRule);
        verify(vaccineTypes, never()).delete(any(VaccineType.class));
        verifyNoInteractions(vaccinations);
    }

    @Test
    void deleteBlockedByVaccination() {
        existing();
        when(protocols.existsByVaccineTypeId(3L)).thenReturn(false);
        when(products.existsByVaccineTypeId(3L)).thenReturn(false);
        when(vaccinations.existsByVaccineType(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteVaccineType(3L)).satisfies(VaccineTypeServiceTest::assertRule);
        verify(vaccineTypes, never()).delete(any(VaccineType.class));
    }

    @Test
    void deletesUnusedType() {
        VaccineType type = existing();
        when(protocols.existsByVaccineTypeId(3L)).thenReturn(false);
        when(products.existsByVaccineTypeId(3L)).thenReturn(false);
        when(vaccinations.existsByVaccineType(3L)).thenReturn(false);

        service.deleteVaccineType(3L);

        verify(vaccineTypes).delete(type);
    }

    @Test
    void deleteUnknownIsNotFound() {
        when(vaccineTypes.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteVaccineType(3L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
