package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.entity.KennelType;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.catalog.repository.KennelTypeRepository;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.customer.api.Species;

/** {@code CatalogQueryApi}: đọc danh mục cho appointment, visit, boarding (06-module-contracts §2). */
@ExtendWith(MockitoExtension.class)
class CatalogQueryServiceTest {

    @Mock ServiceRepository services;
    @Mock KennelTypeRepository kennelTypes;
    @Mock ProductRepository products;
    @Mock VaccinationProtocolRepository protocols;

    CatalogQueryService service;

    @BeforeEach
    void setUp() {
        service = new CatalogQueryService(services, kennelTypes, products, protocols);
    }

    private static Service service(long id, ServiceGroup group, MedicalType type, long price, boolean active) {
        Service s = new Service("Dịch vụ " + id, group, type, price, true, null);
        ReflectionTestUtils.setField(s, "id", id);
        s.setActive(active);
        return s;
    }

    private static VaccinationProtocol protocol(long id, long vaccineTypeId, int dose) {
        VaccinationProtocol p = new VaccinationProtocol(Species.DOG, vaccineTypeId, dose, 21, 6, true);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    void findsService() {
        when(services.findById(1L)).thenReturn(Optional.of(service(1, ServiceGroup.MEDICAL, MedicalType.EXAM, 50_000, true)));

        ServiceInfo info = service.findService(1L).orElseThrow();

        assertThat(info.group()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(info.medicalType()).isEqualTo(MedicalType.EXAM);
        assertThat(info.price()).isEqualTo(50_000);
        assertThat(info.active()).isTrue();
        assertThat(service.findService(2L)).isEmpty();
    }

    @Test
    void emptyServiceIdsSkipsTheQuery() {
        assertThat(service.findServices(List.of())).isEmpty();
        verifyNoInteractions(services);
    }

    @Test
    void kennelTypeCombinesServiceAndKennel() {
        when(services.findById(5L)).thenReturn(Optional.of(service(5, ServiceGroup.BOARDING, null, 150_000, true)));
        when(kennelTypes.findById(5L)).thenReturn(Optional.of(new KennelType(5L, Species.DOG, new BigDecimal("15"))));

        KennelTypeInfo info = service.findKennelType(5L).orElseThrow();

        assertThat(info.nightlyPrice()).isEqualTo(150_000);
        assertThat(info.species()).isEqualTo(Species.DOG);
        assertThat(info.maxWeightKg()).isEqualByComparingTo("15");
        assertThat(info.active()).isTrue();
    }

    @Test
    void nonBoardingServiceIsNotAKennelType() {
        when(services.findById(1L)).thenReturn(Optional.of(service(1, ServiceGroup.GROOMING, null, 50_000, true)));

        assertThat(service.findKennelType(1L)).isEmpty();
        verifyNoInteractions(kennelTypes);
    }

    @Test
    void protocolIsReturnedInDoseOrder() {
        when(protocols.findBySpeciesAndVaccineTypeIdAndActiveTrueOrderByDoseNumberAsc(Species.DOG, 3L))
                .thenReturn(List.of(protocol(1, 3, 1), protocol(2, 3, 2)));

        List<ProtocolDose> doses = service.findProtocol(Species.DOG, 3L);

        assertThat(doses).extracting(ProtocolDose::doseNumber).containsExactly(1, 2);
        assertThat(doses.get(0).requiredForBoarding()).isTrue();
    }

    @Test
    void boardingRequiredVaccineTypesAreDistinctAndSorted() {
        when(protocols.findBySpeciesAndRequiredForBoardingTrueAndActiveTrue(Species.DOG))
                .thenReturn(List.of(protocol(1, 8, 1), protocol(2, 3, 1), protocol(3, 8, 2)));

        assertThat(service.findBoardingRequiredVaccineTypeIds(Species.DOG)).containsExactly(3L, 8L);
    }

    @Test
    void findsProtocolDoseById() {
        when(protocols.findById(1L)).thenReturn(Optional.of(protocol(1, 3, 1)));

        assertThat(service.findProtocolDose(1L)).get().extracting(ProtocolDose::vaccineTypeId).isEqualTo(3L);
        assertThat(service.findProtocolDose(2L)).isEmpty();
    }
}
