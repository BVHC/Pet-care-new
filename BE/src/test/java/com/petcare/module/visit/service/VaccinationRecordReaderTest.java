package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.entity.Vaccination;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.VaccinationRepository;

/** {@link VaccinationRecordReader} (docs/adr/0072): mũi của chính lượt, tên tra theo lô, thiếu tên là dữ liệu hỏng. */
class VaccinationRecordReaderTest {

    private static final long PET = 12L;
    private static final long VISIT = 9001L;

    private final VaccinationRepository vaccinations = mock(VaccinationRepository.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final VaccinationRecordReader reader = new VaccinationRecordReader(vaccinations, catalog,
            Mappers.getMapper(MedicalRecordMapper.class));

    private static Vaccination row(long id, long typeId, long productId) {
        Vaccination v = BeanUtils.instantiateClass(Vaccination.class);
        ReflectionTestUtils.setField(v, "id", id);
        ReflectionTestUtils.setField(v, "petId", PET);
        ReflectionTestUtils.setField(v, "vaccineTypeId", typeId);
        ReflectionTestUtils.setField(v, "visitId", VISIT);
        ReflectionTestUtils.setField(v, "branchId", 3L);
        ReflectionTestUtils.setField(v, "administeredOn", LocalDate.of(2026, 10, 22));
        ReflectionTestUtils.setField(v, "protocolId", 12L);
        ReflectionTestUtils.setField(v, "doseNumber", 1);
        ReflectionTestUtils.setField(v, "productId", productId);
        ReflectionTestUtils.setField(v, "stockLotId", 55L);
        ReflectionTestUtils.setField(v, "orderLineId", 9100L + id);
        ReflectionTestUtils.setField(v, "nextDueDate", LocalDate.of(2027, 10, 22));
        ReflectionTestUtils.setField(v, "recordedBy", 45L);
        return v;
    }

    private static ProductInfo product(long id, String name) {
        return new ProductInfo(id, name, ProductType.VACCINE, false, true, 5L, 150000L, false);
    }

    @Test
    void aVisitWithoutDosesIsAnEmptyListAndNeverCallsTheCatalog() {
        doReturn(List.of()).when(vaccinations).findByPetIdAndVisitIdOrderByIdAsc(PET, VISIT);

        assertThat(reader.forVisit(PET, VISIT)).isEmpty();

        verifyNoInteractions(catalog);
    }

    @Test
    void namesAreLookedUpOncePerBatchEvenForRepeatedTypesAndProductsAndInactiveOnesKeepTheirName() {
        doReturn(List.of(row(1L, 5L, 81L), row(2L, 5L, 81L), row(3L, 6L, 82L))).when(vaccinations)
                .findByPetIdAndVisitIdOrderByIdAsc(PET, VISIT);
        doReturn(Map.of(5L, "Dại", 6L, "5 bệnh")).when(catalog).findVaccineTypeNames(List.of(5L, 6L));
        doReturn(List.of(product(81L, "Rabisin"), product(82L, "Nobivac"))).when(catalog)
                .findProducts(List.of(81L, 82L));

        List<VaccinationRecordResponse> result = reader.forVisit(PET, VISIT);

        assertThat(result).extracting("vaccinationId", "vaccineTypeName", "productName").containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, "Dại", "Rabisin"),
                org.assertj.core.groups.Tuple.tuple(2L, "Dại", "Rabisin"),
                org.assertj.core.groups.Tuple.tuple(3L, "5 bệnh", "Nobivac"));
        verify(catalog, times(1)).findVaccineTypeNames(List.of(5L, 6L));
        verify(catalog, times(1)).findProducts(List.of(81L, 82L));
    }

    @Test
    void aMissingVaccineTypeNameIsCorruptData() {
        doReturn(List.of(row(1L, 5L, 81L))).when(vaccinations).findByPetIdAndVisitIdOrderByIdAsc(PET, VISIT);
        doReturn(Map.of()).when(catalog).findVaccineTypeNames(List.of(5L));
        doReturn(List.of(product(81L, "Rabisin"))).when(catalog).findProducts(List.of(81L));

        assertThatThrownBy(() -> reader.forVisit(PET, VISIT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Loại vaccine #5");
    }

    @Test
    void aMissingProductNameIsCorruptData() {
        doReturn(List.of(row(1L, 5L, 81L))).when(vaccinations).findByPetIdAndVisitIdOrderByIdAsc(PET, VISIT);
        doReturn(Map.of(5L, "Dại")).when(catalog).findVaccineTypeNames(List.of(5L));
        doReturn(List.of()).when(catalog).findProducts(List.of(81L));

        assertThatThrownBy(() -> reader.forVisit(PET, VISIT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sản phẩm #81");
    }

    // ================================================================ latestOfType (visit-v1 #18, docs/adr/0073)

    @Test
    void noDoseOfTheTypeIsEmptyAndNeverCallsTheCatalog() {
        doReturn(java.util.Optional.empty()).when(vaccinations)
                .findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc(PET, 5L);

        assertThat(reader.latestOfType(PET, 5L)).isEmpty();

        verifyNoInteractions(catalog);
    }

    @Test
    void theLatestDoseIsMappedWithItsNames() {
        doReturn(java.util.Optional.of(row(7L, 5L, 81L))).when(vaccinations)
                .findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc(PET, 5L);
        doReturn(Map.of(5L, "Dại")).when(catalog).findVaccineTypeNames(List.of(5L));
        doReturn(List.of(product(81L, "Rabisin"))).when(catalog).findProducts(List.of(81L));

        VaccinationRecordResponse dose = reader.latestOfType(PET, 5L).orElseThrow();

        assertThat(dose.vaccinationId()).isEqualTo(7L);
        assertThat(dose.vaccineTypeName()).isEqualTo("Dại");
        assertThat(dose.productName()).isEqualTo("Rabisin");
        assertThat(dose.lotNumber()).isNull();
    }
}
