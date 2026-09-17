package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreResourceRequest;
import com.petcare.module.organization.dto.UpdateStoreResourceRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.entity.StoreResource;
import com.petcare.module.organization.mapper.StoreResourceMapper;
import com.petcare.module.organization.mapper.StoreResourceMapperImpl;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.module.organization.repository.StoreResourceRepository;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.ResourceType;
import com.petcare.platform.enums.StoreStatus;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-02/06/08 (ConfigureStoreResource). */
@ExtendWith(MockitoExtension.class)
class StoreResourceServiceImplTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StoreResourceRepository storeResourceRepository;

    private final StoreResourceMapper storeResourceMapper = new StoreResourceMapperImpl();

    private StoreResourceServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static Store store(UUID id, StoreStatus status) {
        Store store = new Store(UUID.randomUUID(), "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                "123 Le Loi", "0901234567");
        store.setId(id);
        store.setStatus(status);
        return store;
    }

    private static CreateStoreResourceRequest request(String code, Boolean isActive) {
        return new CreateStoreResourceRequest(code, "Phong kham 1", ResourceType.CLINIC_ROOM, isActive);
    }

    private static StoreResource resource(UUID id, UUID storeId, String code, boolean active) {
        StoreResource resource = new StoreResource(storeId, code, "Phong kham 1", ResourceType.CLINIC_ROOM, active);
        resource.setId(id);
        return resource;
    }

    @BeforeEach
    void setUp() {
        service = new StoreResourceServiceImpl(storeRepository, storeResourceRepository, storeResourceMapper);
    }

    @Test
    void createResource_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null),
                principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createResource_notOwnStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void createResource_organizationAdmin_deniedRegardlessOfScope() {
        // Khác UpdateStore — OrgAdmin KHÔNG được gọi endpoint này dù quản lý đúng Org chứa Store
        // (quyết định PO 2026-09-17, cùng logic ConfigureOperatingHour).
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void createResource_archivedStore_throwsBusinessRuleViolation_RULE_03_06() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.ARCHIVED)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null), actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-06"));
    }

    @Test
    void createResource_duplicateResourceCode_throwsBusinessRuleViolation_RULE_03_08() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.existsByStoreIdAndResourceCode(storeId, "ROOM_01")).thenReturn(true);
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null), actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-08"));
    }

    @Test
    void createResource_raceCondition_dataIntegrityViolation_throwsBusinessRuleViolation_RULE_03_08() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.existsByStoreIdAndResourceCode(storeId, "ROOM_01")).thenReturn(false);
        when(storeResourceRepository.save(any(StoreResource.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_store_resources_code"));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.createResource(storeId, request("ROOM_01", null), actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-08"));
    }

    @Test
    void createResource_isActiveNotSpecified_defaultsToTrue() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.existsByStoreIdAndResourceCode(storeId, "ROOM_01")).thenReturn(false);
        when(storeResourceRepository.save(any(StoreResource.class))).thenAnswer(invocation -> {
            StoreResource entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        var response = service.createResource(storeId, request("ROOM_01", null), actor);

        assertThat(response.resourceCode()).isEqualTo("ROOM_01");
        assertThat(response.storeId()).isEqualTo(storeId);
        assertThat(response.resourceType()).isEqualTo(ResourceType.CLINIC_ROOM);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void createResource_isActiveExplicitFalse_persistsInactive() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.existsByStoreIdAndResourceCode(storeId, "ROOM_01")).thenReturn(false);
        when(storeResourceRepository.save(any(StoreResource.class))).thenAnswer(invocation -> {
            StoreResource entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        var response = service.createResource(storeId, request("ROOM_01", false), actor);

        assertThat(response.isActive()).isFalse();
    }

    @Test
    void updateResource_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Ten moi", null),
                principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateResource_notOwnStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Ten moi", null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateResource_archivedStore_throwsBusinessRuleViolation_RULE_03_06() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.ARCHIVED)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Ten moi", null), actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-06"));
    }

    @Test
    void updateResource_resourceNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.findById(resourceId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Ten moi", null), actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateResource_belongsToDifferentStore_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        when(storeResourceRepository.findById(resourceId))
                .thenReturn(Optional.of(resource(resourceId, otherStoreId, "ROOM_01", true)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        assertThatThrownBy(() -> service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Ten moi", null), actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateResource_partialUpdate_keepsUnspecifiedFieldsUnchanged() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        StoreResource existing = resource(resourceId, storeId, "ROOM_01", true);
        when(storeResourceRepository.findById(resourceId)).thenReturn(Optional.of(existing));
        when(storeResourceRepository.save(any(StoreResource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        var response = service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest(null, false), actor);

        assertThat(response.isActive()).isFalse();
        assertThat(response.resourceName()).isEqualTo("Phong kham 1"); // giữ nguyên, không gửi field này
        assertThat(response.resourceCode()).isEqualTo("ROOM_01"); // bất biến, không có field để sửa
    }

    @Test
    void updateResource_valid_updatesResourceName() {
        UUID storeId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.DRAFT)));
        StoreResource existing = resource(resourceId, storeId, "ROOM_01", true);
        when(storeResourceRepository.findById(resourceId)).thenReturn(Optional.of(existing));
        when(storeResourceRepository.save(any(StoreResource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId);

        var response = service.updateResource(storeId, resourceId,
                new UpdateStoreResourceRequest("Phong kham VIP", null), actor);

        assertThat(response.resourceName()).isEqualTo("Phong kham VIP");
        assertThat(response.isActive()).isTrue(); // giữ nguyên, không gửi field này
    }

    @Test
    void listResources_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listResources(storeId, principal(UserRole.SUPER_ADMIN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listResources_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, StoreStatus.ACTIVE);
        existing.setOrganizationId(organizationId);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.listResources(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listResources_storeManager_differentStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, StoreStatus.ACTIVE)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.listResources(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listResources_storeManager_ownStore_returnsAllResources() {
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeResourceRepository.findAllByStoreId(storeId)).thenReturn(java.util.List.of(
                resource(UUID.randomUUID(), storeId, "ROOM_01", true),
                resource(UUID.randomUUID(), storeId, "TABLE_01", false)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, existing.getOrganizationId(), storeId);

        var response = service.listResources(storeId, actor);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting("resourceCode").containsExactlyInAnyOrder("ROOM_01", "TABLE_01");
    }
}
