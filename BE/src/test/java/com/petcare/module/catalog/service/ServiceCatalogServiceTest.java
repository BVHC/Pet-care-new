package com.petcare.module.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.KennelTypeSpec;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.entity.KennelType;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapperImpl;
import com.petcare.module.catalog.repository.KennelTypeRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;

/** UC30: BR-SP-04 (loại chuồng), BR-SP-06 (loại Khám/Tiêm); đổi giá ghi audit PRICE_CHANGED. */
@ExtendWith(MockitoExtension.class)
class ServiceCatalogServiceTest {

    @Mock ServiceRepository services;
    @Mock KennelTypeRepository kennelTypes;
    @Mock AuditRecorder auditRecorder;

    ServiceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ServiceCatalogService(services, kennelTypes, auditRecorder, new CatalogMapperImpl());
    }

    private static CreateServiceRequest create(ServiceGroup group, MedicalType medicalType, long price,
            KennelTypeSpec kennel) {
        return new CreateServiceRequest(" Dịch vụ ", group, medicalType, price, null, null, kennel);
    }

    private static KennelTypeSpec kennel(Species species, String weight) {
        return new KennelTypeSpec(species, weight == null ? null : new BigDecimal(weight));
    }

    private void saves() {
        when(services.save(any(Service.class))).thenAnswer(inv -> {
            Service saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            return saved;
        });
    }

    private static void assertRule(Throwable thrown, String ruleId) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo(ruleId));
    }

    @Test
    void medicalServiceNeedsMedicalType_BR_SP_06() {
        assertThatThrownBy(() -> service.createService(create(ServiceGroup.MEDICAL, null, 1000, null)))
                .satisfies(t -> assertRule(t, "BR-SP-06"));
    }

    @Test
    void groomingServiceCannotHaveMedicalType_BR_SP_06() {
        assertThatThrownBy(() -> service.createService(create(ServiceGroup.GROOMING, MedicalType.EXAM, 1000, null)))
                .satisfies(t -> assertRule(t, "BR-SP-06"));
    }

    @Test
    void createsMedicalService() {
        saves();

        ServiceResponse response = service.createService(create(ServiceGroup.MEDICAL, MedicalType.VACCINE, 1000, null));

        assertThat(response.name()).isEqualTo("Dịch vụ");
        assertThat(response.medicalType()).isEqualTo(MedicalType.VACCINE);
        assertThat(response.priceIsFrom()).isTrue();
        assertThat(response.kennelType()).isNull();
        verifyNoInteractions(kennelTypes);
    }

    @Test
    void duplicateNameIsRejected() {
        when(services.existsByName("Dịch vụ")).thenReturn(true);

        assertThatThrownBy(() -> service.createService(create(ServiceGroup.GROOMING, null, 1000, null)))
                .isInstanceOf(DuplicateCatalogEntryException.class);
    }

    @Test
    void kennelPriceMustBePositive_BR_SP_04() {
        assertThatThrownBy(() -> service.createService(
                create(ServiceGroup.BOARDING, null, 0, kennel(Species.DOG, "10"))))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void kennelNeedsSpec_BR_SP_04() {
        assertThatThrownBy(() -> service.createService(create(ServiceGroup.BOARDING, null, 1000, null)))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void kennelNeedsSpecies_BR_SP_04() {
        assertThatThrownBy(() -> service.createService(
                create(ServiceGroup.BOARDING, null, 1000, kennel(null, "10"))))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void kennelWeightMustBeValid_BR_SP_04() {
        for (String weight : new String[] {null, "0", "-1", "10000", "1.234"}) {
            assertThatThrownBy(() -> service.createService(
                    create(ServiceGroup.BOARDING, null, 1000, kennel(Species.DOG, weight))))
                    .as("weight %s", weight)
                    .satisfies(t -> assertRule(t, "BR-SP-04"));
        }
    }

    @Test
    void nonBoardingServiceCannotHaveKennelSpec_BR_SP_04() {
        assertThatThrownBy(() -> service.createService(
                create(ServiceGroup.GROOMING, null, 1000, kennel(Species.DOG, "10"))))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void createsKennelTypeWithSpec() {
        saves();
        when(kennelTypes.save(any(KennelType.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceResponse response = service.createService(
                create(ServiceGroup.BOARDING, null, 150_000, kennel(Species.DOG, "12.5")));

        assertThat(response.kennelType().species()).isEqualTo(Species.DOG);
        assertThat(response.kennelType().maxWeightKg()).isEqualByComparingTo("12.5");
        ArgumentCaptor<KennelType> captor = ArgumentCaptor.forClass(KennelType.class);
        verify(kennelTypes).save(captor.capture());
        assertThat(captor.getValue().getServiceId()).isEqualTo(9L);
    }

    private Service existing(ServiceGroup group, MedicalType type, long price) {
        Service s = new Service("Dịch vụ", group, type, price, true, null);
        ReflectionTestUtils.setField(s, "id", 9L);
        when(services.findById(9L)).thenReturn(Optional.of(s));
        lenient().when(services.saveAndFlush(any(Service.class))).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    @Test
    void priceChangeIsAudited() {
        existing(ServiceGroup.GROOMING, null, 100_000);

        service.updateService(9L, new UpdateServiceRequest(null, null, 120_000L, null, null, null, null));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("PRICE_CHANGED");
        assertThat(captor.getValue().entityType()).isEqualTo("services");
        assertThat(captor.getValue().before()).isEqualTo(new PriceAuditSnapshot(100_000));
        assertThat(captor.getValue().after()).isEqualTo(new PriceAuditSnapshot(120_000));
    }

    @Test
    void unchangedPriceIsNotAudited() {
        existing(ServiceGroup.GROOMING, null, 100_000);

        ServiceResponse response = service.updateService(9L,
                new UpdateServiceRequest(null, null, null, null, "Mô tả", false, null));

        assertThat(response.isActive()).isFalse();
        verify(auditRecorder, never()).record(any());
    }

    @Test
    void cannotSetMedicalTypeOnGrooming_BR_SP_06() {
        existing(ServiceGroup.GROOMING, null, 100_000);

        assertThatThrownBy(() -> service.updateService(9L,
                new UpdateServiceRequest(null, MedicalType.EXAM, null, null, null, null, null)))
                .satisfies(t -> assertRule(t, "BR-SP-06"));
    }

    @Test
    void changesMedicalType() {
        existing(ServiceGroup.MEDICAL, MedicalType.EXAM, 100_000);

        ServiceResponse response = service.updateService(9L,
                new UpdateServiceRequest(null, MedicalType.VACCINE, null, null, null, null, null));

        assertThat(response.medicalType()).isEqualTo(MedicalType.VACCINE);
    }

    @Test
    void cannotSetKennelSpecOnNonBoarding_BR_SP_04() {
        existing(ServiceGroup.MEDICAL, MedicalType.EXAM, 100_000);

        assertThatThrownBy(() -> service.updateService(9L,
                new UpdateServiceRequest(null, null, null, null, null, null, kennel(Species.CAT, "5"))))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void kennelPriceCannotDropToZero_BR_SP_04() {
        existing(ServiceGroup.BOARDING, null, 150_000);

        assertThatThrownBy(() -> service.updateService(9L,
                new UpdateServiceRequest(null, null, 0L, null, null, null, null)))
                .satisfies(t -> assertRule(t, "BR-SP-04"));
    }

    @Test
    void partialKennelSpecKeepsOtherField() {
        existing(ServiceGroup.BOARDING, null, 150_000);
        KennelType kennel = new KennelType(9L, Species.DOG, new BigDecimal("10"));
        when(kennelTypes.findById(9L)).thenReturn(Optional.of(kennel));
        when(kennelTypes.save(any(KennelType.class))).thenAnswer(inv -> inv.getArgument(0));

        ServiceResponse response = service.updateService(9L,
                new UpdateServiceRequest(null, null, null, null, null, null, kennel(null, "20")));

        assertThat(response.kennelType().species()).isEqualTo(Species.DOG);
        assertThat(response.kennelType().maxWeightKg()).isEqualByComparingTo("20");
    }
}
