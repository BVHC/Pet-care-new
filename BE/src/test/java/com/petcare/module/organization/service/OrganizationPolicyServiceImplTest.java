package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.UpdateOrganizationPolicyRequest;
import com.petcare.module.organization.entity.OrganizationPolicy;
import com.petcare.module.organization.mapper.OrganizationPolicyMapper;
import com.petcare.module.organization.mapper.OrganizationPolicyMapperImpl;
import com.petcare.module.organization.repository.OrganizationPolicyRepository;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.platform.enums.SecurityFrameworkLevel;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ConcurrencyConflictException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-09 (ManageOrganizationPolicy). */
@ExtendWith(MockitoExtension.class)
class OrganizationPolicyServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationPolicyRepository organizationPolicyRepository;

    private final OrganizationPolicyMapper organizationPolicyMapper = new OrganizationPolicyMapperImpl();

    private OrganizationPolicyServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static OrganizationPolicy policy(UUID organizationId, long version) {
        OrganizationPolicy policy = new OrganizationPolicy(organizationId);
        policy.setVersion(version);
        return policy;
    }

    private static UpdateOrganizationPolicyRequest request(Integer refundWindowDays, Long version) {
        return new UpdateOrganizationPolicyRequest(refundWindowDays, null, null, null, version);
    }

    @BeforeEach
    void setUp() {
        service = new OrganizationPolicyServiceImpl(organizationRepository, organizationPolicyRepository,
                organizationPolicyMapper);
    }

    // --- getPolicy ---

    @Test
    void getPolicy_organizationNotFound_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(false);

        assertThatThrownBy(() -> service.getPolicy(organizationId, principal(UserRole.SUPER_ADMIN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPolicy_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.getPolicy(organizationId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getPolicy_storeManager_deniedEntirely() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.getPolicy(organizationId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getPolicy_neverConfigured_returnsDefaultsWithVersionZero() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        var response = service.getPolicy(organizationId, actor);

        assertThat(response.version()).isZero();
        assertThat(response.refundWindowDays()).isEqualTo(7);
        assertThat(response.refundRequiresApproval()).isTrue();
        assertThat(response.dataRetentionDays()).isEqualTo(730);
        assertThat(response.securityFrameworkLevel()).isEqualTo(SecurityFrameworkLevel.STANDARD);
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void getPolicy_existingRow_returnsPersistedValues() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        OrganizationPolicy existing = policy(organizationId, 3L);
        existing.setRefundWindowDays(14);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.SUPER_ADMIN, null, null);

        var response = service.getPolicy(organizationId, actor);

        assertThat(response.refundWindowDays()).isEqualTo(14);
        assertThat(response.version()).isEqualTo(3L);
    }

    // --- updatePolicy ---

    @Test
    void updatePolicy_organizationNotFound_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(false);

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(14, 0L),
                principal(UserRole.SUPER_ADMIN, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePolicy_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null);

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(14, 0L), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updatePolicy_storeManager_deniedEntirely() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(14, 0L), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updatePolicy_absentRow_versionZero_lazyCreatesWithDefaultsAndOverrides() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
        when(organizationPolicyRepository.save(any(OrganizationPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        var response = service.updatePolicy(organizationId, request(14, 0L), actor);

        assertThat(response.refundWindowDays()).isEqualTo(14);
        assertThat(response.dataRetentionDays()).isEqualTo(730); // giữ default, không gửi field này
        assertThat(response.securityFrameworkLevel()).isEqualTo(SecurityFrameworkLevel.STANDARD);
    }

    @Test
    void updatePolicy_absentRow_nonZeroVersion_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(14, 5L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);

        verify(organizationPolicyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_existingRow_matchingVersion_updatesAndIncrementsVersion() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        OrganizationPolicy existing = policy(organizationId, 2L);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));
        when(organizationPolicyRepository.save(any(OrganizationPolicy.class))).thenAnswer(invocation -> {
            OrganizationPolicy saved = invocation.getArgument(0);
            saved.setVersion(saved.getVersion() + 1); // mô phỏng Hibernate @Version tự tăng lúc flush
            return saved;
        });
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        var response = service.updatePolicy(organizationId, request(21, 2L), actor);

        assertThat(response.refundWindowDays()).isEqualTo(21);
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void updatePolicy_existingRow_staleVersion_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        OrganizationPolicy existing = policy(organizationId, 5L);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(21, 3L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);

        verify(organizationPolicyRepository, never()).save(any());
    }

    @Test
    void updatePolicy_insertRaceCondition_dataIntegrityViolation_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
        when(organizationPolicyRepository.save(any(OrganizationPolicy.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_org_policies_org"));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        assertThatThrownBy(() -> service.updatePolicy(organizationId, request(14, 0L), actor))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void updatePolicy_partialUpdate_keepsUnspecifiedFieldsUnchanged() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        OrganizationPolicy existing = policy(organizationId, 1L);
        existing.setDataRetentionDays(365);
        existing.setSecurityFrameworkLevel(SecurityFrameworkLevel.ENHANCED);
        when(organizationPolicyRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(existing));
        when(organizationPolicyRepository.save(any(OrganizationPolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, organizationId, null);

        var response = service.updatePolicy(organizationId, request(30, 1L), actor);

        assertThat(response.refundWindowDays()).isEqualTo(30);
        assertThat(response.dataRetentionDays()).isEqualTo(365); // giữ nguyên, không gửi field này
        assertThat(response.securityFrameworkLevel()).isEqualTo(SecurityFrameworkLevel.ENHANCED); // giữ nguyên
    }
}
