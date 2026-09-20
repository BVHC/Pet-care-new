package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.UpdateStorePolicyRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.entity.StorePolicy;
import com.petcare.module.organization.mapper.StorePolicyMapper;
import com.petcare.module.organization.mapper.StorePolicyMapperImpl;
import com.petcare.module.organization.repository.StorePolicyRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.SurchargeType;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-10 (ConfigureStorePolicy). */
@ExtendWith(MockitoExtension.class)
class StorePolicyServiceImplTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StorePolicyRepository storePolicyRepository;

    private final StorePolicyMapper storePolicyMapper = new StorePolicyMapperImpl();

    private StorePolicyServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static Store store(UUID id, UUID organizationId, StoreStatus status) {
        Store store = new Store(organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                "123 Le Loi", "0901234567");
        store.setId(id);
        store.setStatus(status);
        return store;
    }

    private static StorePolicy policy(UUID storeId, long version) {
        StorePolicy policy = new StorePolicy(storeId);
        policy.setVersion(version);
        return policy;
    }

    private static UpdateStorePolicyRequest request(Boolean surchargeEnabled, SurchargeType surchargeType,
                                                      BigDecimal surchargeValue, Long version) {
        return new UpdateStorePolicyRequest(surchargeEnabled, surchargeType, surchargeValue, version);
    }

    @BeforeEach
    void setUp() {
        service = new StorePolicyServiceImpl(storeRepository, storePolicyRepository, storePolicyMapper);
    }

    // --- getPolicy ---

    @Test
    void getPolicy_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPolicy(storeId, principal(UserRole.SUPER_ADMIN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPolicy_orgAdminDifferentOrg_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.getPolicy(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getPolicy_storeManagerDifferentStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.getPolicy(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getPolicy_neverConfigured_returnsDefaults() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        var response = service.getPolicy(storeId, actor);

        assertThat(response.version()).isZero();
        assertThat(response.surchargeEnabled()).isFalse();
        assertThat(response.surchargeType()).isNull();
        assertThat(response.surchargeValue()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void getPolicy_existingRow_returnsPersistedValues() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        StorePolicy existing = policy(storeId, 3L);
        existing.setSurchargeEnabled(true);
        existing.setSurchargeType(SurchargeType.FIXED_AMOUNT);
        existing.setSurchargeValue(BigDecimal.valueOf(10000));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);

        var response = service.getPolicy(storeId, actor);

        assertThat(response.surchargeEnabled()).isTrue();
        assertThat(response.surchargeType()).isEqualTo(SurchargeType.FIXED_AMOUNT);
        assertThat(response.version()).isEqualTo(3L);
    }

    // --- updatePolicy ---

    @Test
    void updatePolicy_storeNotFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 0L),
                principal(UserRole.STORE_MANAGER, UUID.randomUUID(), storeId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePolicy_notStoreManager_deniedEntirely() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));

        // Org Admin của đúng Organization sở hữu Store vẫn bị chặn — PATCH không có ngoại lệ admin.
        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 0L),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId, null)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updatePolicy_storeManagerDifferentStore_deniedByScope() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 0L),
                principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID())))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updatePolicy_archivedStore_throwsBusinessRuleViolation() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ARCHIVED)));

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 0L),
                principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void updatePolicy_absentRow_versionZero_lazyCreates() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        when(storePolicyRepository.save(any(StorePolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        var response = service.updatePolicy(storeId,
                request(true, SurchargeType.PERCENTAGE, BigDecimal.valueOf(5), 0L), actor);

        assertThat(response.surchargeEnabled()).isTrue();
        assertThat(response.surchargeType()).isEqualTo(SurchargeType.PERCENTAGE);
        assertThat(response.surchargeValue()).isEqualByComparingTo("5");
    }

    @Test
    void updatePolicy_absentRow_nonZeroVersion_throwsConcurrencyConflict() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 5L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);

        verify(storePolicyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_existingRow_matchingVersion_updatesAndIncrementsVersion() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        StorePolicy existing = policy(storeId, 2L);
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.of(existing));
        when(storePolicyRepository.save(any(StorePolicy.class))).thenAnswer(invocation -> {
            StorePolicy saved = invocation.getArgument(0);
            saved.setVersion(saved.getVersion() + 1);
            return saved;
        });
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        var response = service.updatePolicy(storeId,
                request(true, SurchargeType.FIXED_AMOUNT, BigDecimal.valueOf(20000), 2L), actor);

        assertThat(response.surchargeValue()).isEqualByComparingTo("20000");
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void updatePolicy_existingRow_staleVersion_throwsConcurrencyConflict() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        StorePolicy existing = policy(storeId, 5L);
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 3L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);

        verify(storePolicyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_insertRaceCondition_throwsConcurrencyConflict() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        when(storePolicyRepository.save(any(StorePolicy.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_store_policies_store"));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(false, null, null, 0L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void updatePolicy_partialUpdate_keepsUnspecifiedFieldsUnchanged() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        StorePolicy existing = policy(storeId, 1L);
        existing.setSurchargeEnabled(true);
        existing.setSurchargeType(SurchargeType.PERCENTAGE);
        existing.setSurchargeValue(BigDecimal.valueOf(3));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.of(existing));
        when(storePolicyRepository.save(any(StorePolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        // Chỉ gửi surchargeValue mới, không đổi surchargeEnabled/surchargeType.
        var response = service.updatePolicy(storeId, request(null, null, BigDecimal.valueOf(4), 1L), actor);

        assertThat(response.surchargeEnabled()).isTrue();
        assertThat(response.surchargeType()).isEqualTo(SurchargeType.PERCENTAGE);
        assertThat(response.surchargeValue()).isEqualByComparingTo("4");
    }

    @Test
    void updatePolicy_enabledWithoutTypeOrValue_throwsBusinessRuleViolation() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.updatePolicy(storeId, request(true, null, null, 0L), actor))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(storePolicyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_percentageOver100_throwsBusinessRuleViolation() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.updatePolicy(storeId,
                request(true, SurchargeType.PERCENTAGE, BigDecimal.valueOf(150), 0L), actor))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void updatePolicy_disabling_clearsTypeAndValue() {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, StoreStatus.ACTIVE)));
        StorePolicy existing = policy(storeId, 1L);
        existing.setSurchargeEnabled(true);
        existing.setSurchargeType(SurchargeType.FIXED_AMOUNT);
        existing.setSurchargeValue(BigDecimal.valueOf(15000));
        when(storePolicyRepository.findByStoreId(storeId)).thenReturn(Optional.of(existing));
        when(storePolicyRepository.save(any(StorePolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        var response = service.updatePolicy(storeId, request(false, null, null, 1L), actor);

        assertThat(response.surchargeEnabled()).isFalse();
        assertThat(response.surchargeType()).isNull();
        assertThat(response.surchargeValue()).isNull();
    }
}
