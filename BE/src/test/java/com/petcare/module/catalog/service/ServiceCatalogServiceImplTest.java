package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.RequiredResourceItem;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.ServiceRequiredResource;
import com.petcare.module.catalog.mapper.ServiceMapper;
import com.petcare.module.catalog.mapper.ServiceMapperImpl;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.ServiceRequiredResourceRepository;
import com.petcare.platform.enums.ResourceType;
import com.petcare.platform.enums.ServiceCategory;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-05-03, RULE-05-04, RULE-02-05 (cách ly tenant). */
@ExtendWith(MockitoExtension.class)
class ServiceCatalogServiceImplTest {

    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private ServiceRequiredResourceRepository requiredResourceRepository;

    private final ServiceMapper serviceMapper = new ServiceMapperImpl();

    private ServiceCatalogServiceImpl serviceCatalogService;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId).build();
    }

    private static CreateServiceRequest request(String code) {
        return new CreateServiceRequest(code, "Kham tong quat", ServiceCategory.CLINICAL, "150000.00", 30, true,
                List.of(new RequiredResourceItem(ResourceType.CLINIC_ROOM, 1)));
    }

    private static Service service(UUID id, UUID organizationId, String code) {
        Service service = new Service(organizationId, code, "Kham tong quat", ServiceCategory.CLINICAL,
                new BigDecimal("150000.00"), 30, true);
        service.setId(id);
        return service;
    }

    @BeforeEach
    void setUp() {
        serviceCatalogService = new ServiceCatalogServiceImpl(serviceRepository, requiredResourceRepository, serviceMapper);
    }

    @Test
    void createService_duplicateCodeInOrg_throwsBusinessRuleViolation_RULE_05_03() {
        UUID organizationId = UUID.randomUUID();
        when(serviceRepository.existsByOrganizationIdAndCode(organizationId, "SV01")).thenReturn(true);

        assertThatThrownBy(() -> serviceCatalogService.createService(request("SV01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-05-03"));
    }

    @Test
    void createService_raceCondition_dataIntegrityViolation_throwsBusinessRuleViolation() {
        UUID organizationId = UUID.randomUUID();
        when(serviceRepository.existsByOrganizationIdAndCode(organizationId, "SV01")).thenReturn(false);
        when(serviceRepository.save(any(Service.class))).thenThrow(new DataIntegrityViolationException("uq_services_org_code"));

        assertThatThrownBy(() -> serviceCatalogService.createService(request("SV01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-05-03"));
    }

    @Test
    void createService_persistsRequiredResourcesInline_RULE_05_03() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.existsByOrganizationIdAndCode(organizationId, "SV01")).thenReturn(false);
        when(serviceRepository.save(any(Service.class))).thenAnswer(invocation -> {
            Service service = invocation.getArgument(0);
            service.setId(serviceId);
            return service;
        });
        when(requiredResourceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = serviceCatalogService.createService(request("SV01"), principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.requiredResources()).hasSize(1);
        assertThat(response.requiredResources().get(0).resourceType()).isEqualTo(ResourceType.CLINIC_ROOM);
    }

    @Test
    void updateService_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service(serviceId, organizationId, "SV01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> serviceCatalogService.updateService(serviceId,
                new UpdateServiceRequest(null, null, null, null, null, null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(serviceRepository, never()).save(any());
    }

    @Test
    void updateService_notFound_throwsResourceNotFound() {
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceCatalogService.updateService(serviceId,
                new UpdateServiceRequest(null, null, null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateService_requiredResourcesProvided_replacesAsWhole_A1() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service(serviceId, organizationId, "SV01")));
        when(serviceRepository.save(any(Service.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requiredResourceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        serviceCatalogService.updateService(serviceId,
                new UpdateServiceRequest(null, null, null, null, null,
                        List.of(new RequiredResourceItem(ResourceType.GROOMING_TABLE, 2))),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        verify(requiredResourceRepository).deleteAllByServiceId(serviceId);
    }

    @Test
    void updateService_requiredResourcesOmitted_keepsExisting() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service(serviceId, organizationId, "SV01")));
        when(serviceRepository.save(any(Service.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requiredResourceRepository.findAllByServiceId(serviceId))
                .thenReturn(List.of(new ServiceRequiredResource(serviceId, ResourceType.CLINIC_ROOM, 1)));

        var response = serviceCatalogService.updateService(serviceId,
                new UpdateServiceRequest("Ten moi", null, null, null, null, null),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        verify(requiredResourceRepository, never()).deleteAllByServiceId(any());
        assertThat(response.requiredResources()).hasSize(1);
    }

    @Test
    void configureAvailability_neverTouchesMasterIsActive_RULE_05_04() {
        // RULE-05-04 — ManageService (đổi is_active gốc) và ConfigureServiceAvailability (đổi
        // override) là 2 command khác nhau; updateService chỉ được đổi is_active GỐC (Organization),
        // không đụng store_services — verify không có tương tác nào tới StoreServiceOverride ở đây.
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service existing = service(serviceId, organizationId, "SV01");
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(Service.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = serviceCatalogService.updateService(serviceId,
                new UpdateServiceRequest(null, null, null, null, false, null),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.isActive()).isFalse();
    }

    @Test
    void hasActiveService_organizationHasActiveService_returnsTrue() {
        // RULE-03-02 (ActivateStore, Module 03, điều kiện 3) — điểm tích hợp cross-module.
        UUID organizationId = UUID.randomUUID();
        when(serviceRepository.existsByOrganizationIdAndIsActiveTrue(organizationId)).thenReturn(true);

        assertThat(serviceCatalogService.hasActiveService(organizationId)).isTrue();
    }

    @Test
    void hasActiveService_organizationHasNoActiveService_returnsFalse() {
        UUID organizationId = UUID.randomUUID();
        when(serviceRepository.existsByOrganizationIdAndIsActiveTrue(organizationId)).thenReturn(false);

        assertThat(serviceCatalogService.hasActiveService(organizationId)).isFalse();
    }
}
