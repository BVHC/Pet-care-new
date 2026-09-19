package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateOrganizationRequest;
import com.petcare.module.organization.dto.UpdateOrganizationRequest;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.mapper.OrganizationMapper;
import com.petcare.module.organization.mapper.OrganizationMapperImpl;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.platform.enums.OrganizationStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-01, RULE-02-01/05 (cách ly tenant). */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    private final OrganizationMapper organizationMapper = new OrganizationMapperImpl();

    private OrganizationServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId).build();
    }

    private static Organization organization(UUID id, String code) {
        Organization organization = new Organization(code, "BVHC Chain", null, null);
        organization.setId(id);
        return organization;
    }

    @BeforeEach
    void setUp() {
        service = new OrganizationServiceImpl(organizationRepository, organizationMapper);
    }

    @Test
    void createOrganization_duplicateCode_throwsBusinessRuleViolation_RULE_03_01() {
        CreateOrganizationRequest request = new CreateOrganizationRequest("BVHC", "BVHC Chain", null, null);
        when(organizationRepository.existsByCode("BVHC")).thenReturn(true);

        assertThatThrownBy(() -> service.createOrganization(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-01"));
    }

    @Test
    void createOrganization_uniqueCode_savesAndReturnsActive() {
        CreateOrganizationRequest request = new CreateOrganizationRequest("BVHC", "BVHC Chain", "0101234567", "123 Le Loi");
        when(organizationRepository.existsByCode("BVHC")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0);
            organization.setId(UUID.randomUUID());
            return organization;
        });

        var response = service.createOrganization(request);

        assertThat(response.code()).isEqualTo("BVHC");
        assertThat(response.status()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void getOrganization_notFound_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrganization(organizationId, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getOrganization_organizationAdmin_differentOrg_deniedByScope_RULE_02_05() {
        UUID organizationId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.getOrganization(organizationId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getOrganization_organizationAdmin_ownOrg_allowed() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization(organizationId, "BVHC")));

        var response = service.getOrganization(organizationId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void updateOrganization_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UpdateOrganizationRequest request = new UpdateOrganizationRequest("New name", null, null);
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateOrganization(organizationId, request, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateOrganization_codeIsImmutable_nameAndAddressUpdated() {
        UUID organizationId = UUID.randomUUID();
        Organization existing = organization(organizationId, "BVHC");
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(existing));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateOrganizationRequest request = new UpdateOrganizationRequest("BVHC Chain 2.0", null, "456 Nguyen Hue");

        var response = service.updateOrganization(organizationId, request, principal(UserRole.SUPER_ADMIN, null));

        assertThat(response.code()).isEqualTo("BVHC");
        assertThat(response.name()).isEqualTo("BVHC Chain 2.0");
        assertThat(response.address()).isEqualTo("456 Nguyen Hue");
    }

    @Test
    void updateOrganization_concurrentModification_throwsConcurrencyConflict() {
        // 2 request PATCH đồng thời trên cùng Organization đụng version conflict thật (Organization
        // có @Version qua BaseEntity) — save() phải bắt ObjectOptimisticLockingFailureException và
        // trả về 409 CONCURRENCY_CONFLICT sạch, không để lọt xuống 500 (handleGeneric).
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization(organizationId, "BVHC")));
        when(organizationRepository.save(any(Organization.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Organization.class, organizationId));
        UpdateOrganizationRequest request = new UpdateOrganizationRequest("BVHC Chain 2.0", null, null);

        assertThatThrownBy(() -> service.updateOrganization(organizationId, request, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void listOrganizations_superAdmin_seesAll() {
        Pageable pageable = PageRequest.of(0, 20);
        Organization organization = organization(UUID.randomUUID(), "BVHC");
        when(organizationRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(organization)));

        PageResponse<?> page = service.listOrganizations(principal(UserRole.SUPER_ADMIN, null), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void listOrganizations_organizationAdmin_onlySeesOwnOrg() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization(organizationId, "BVHC")));
        Pageable pageable = PageRequest.of(0, 20);

        PageResponse<?> page = service.listOrganizations(principal(UserRole.ORGANIZATION_ADMIN, organizationId), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void listOrganizations_organizationAdmin_pageBeyondOwnOrg_returnsEmptyContent() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization(organizationId, "BVHC")));
        Pageable pageable = PageRequest.of(1, 20);

        PageResponse<?> page = service.listOrganizations(principal(UserRole.ORGANIZATION_ADMIN, organizationId), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).isEmpty();
    }

    @Test
    void updateOrganization_blankName_throwsBusinessRuleViolation_RULE_03_01() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization(organizationId, "BVHC")));
        UpdateOrganizationRequest request = new UpdateOrganizationRequest("   ", null, null);

        assertThatThrownBy(() -> service.updateOrganization(organizationId, request, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-01"));
    }
}
