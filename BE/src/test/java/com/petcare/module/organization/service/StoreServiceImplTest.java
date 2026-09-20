package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.mapper.StoreMapper;
import com.petcare.module.organization.mapper.StoreMapperImpl;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-01, RULE-02-01/05 (cách ly tenant). */
@ExtendWith(MockitoExtension.class)
class StoreServiceImplTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private OrganizationRepository organizationRepository;

    private final StoreMapper storeMapper = new StoreMapperImpl();

    private StoreServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId) {
        return principal(role, organizationId, null);
    }

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static CreateStoreRequest request(String code) {
        return new CreateStoreRequest(code, "Chi nhanh Q1", FacilityType.RETAIL_STORE, "123 Le Loi", "0901234567");
    }

    private static Store store(UUID id, UUID organizationId, String code) {
        Store store = new Store(organizationId, code, "Chi nhanh Q1", FacilityType.RETAIL_STORE, "123 Le Loi", "0901234567");
        store.setId(id);
        return store;
    }

    @BeforeEach
    void setUp() {
        service = new StoreServiceImpl(storeRepository, organizationRepository, storeMapper);
    }

    @Test
    void createStore_orgNotFound_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(false);

        assertThatThrownBy(() -> service.createStore(organizationId, request("HN01"), principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStore_duplicateCodeInOrg_throwsBusinessRuleViolation_RULE_03_01() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(storeRepository.existsByOrganizationIdAndCode(organizationId, "HN01")).thenReturn(true);

        assertThatThrownBy(() -> service.createStore(organizationId, request("HN01"), principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-01"));
    }

    @Test
    void createStore_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.createStore(organizationId, request("HN01"), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void createStore_organizationAdmin_ownOrg_savesAsDraft() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(storeRepository.existsByOrganizationIdAndCode(organizationId, "HN01")).thenReturn(false);
        when(storeRepository.save(any(Store.class))).thenAnswer(invocation -> {
            Store store = invocation.getArgument(0);
            store.setId(UUID.randomUUID());
            return store;
        });

        var response = service.createStore(organizationId, request("HN01"),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.code()).isEqualTo("HN01");
        assertThat(response.organizationId()).isEqualTo(organizationId);
        assertThat(response.status()).isEqualTo(StoreStatus.DRAFT);
    }

    @Test
    void createStore_raceCondition_dataIntegrityViolation_throwsBusinessRuleViolation() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(storeRepository.existsByOrganizationIdAndCode(organizationId, "HN01")).thenReturn(false);
        when(storeRepository.save(any(Store.class))).thenThrow(new DataIntegrityViolationException("uq_stores_org_code"));

        assertThatThrownBy(() -> service.createStore(organizationId, request("HN01"), principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-01"));
    }

    @Test
    void getStore_notFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStore(storeId, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getStore_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.getStore(storeId, actor)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getStore_organizationAdmin_ownOrg_allowed() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));

        var response = service.getStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.storeId()).isEqualTo(storeId);
    }

    @Test
    void getStore_storeManager_differentStore_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.getStore(storeId, actor)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void getStore_storeManager_ownStore_allowed() {
        // Bug fix: StoreManager sửa được Store của mình (UpdateStore) nhưng trước đây không xem
        // được (getStore dùng assertCanManageOrganization, không nhận STORE_MANAGER) — bất nhất
        // với UpdateStore/ConfigureOperatingHour/ConfigureStoreResource, vốn đều cho phép.
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));

        var response = service.getStore(storeId, principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.storeId()).isEqualTo(storeId);
    }

    @Test
    void updateStore_notFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest("Ten moi", null, null),
                principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStore_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest("Ten moi", null, null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateStore_organizationAdmin_anyStoreInOwnOrg_updatesFields() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.save(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateStore(storeId, new UpdateStoreRequest("Chi nhanh moi", "456 Nguyen Hue", "0987654321"),
                principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.name()).isEqualTo("Chi nhanh moi");
        assertThat(response.address()).isEqualTo("456 Nguyen Hue");
        assertThat(response.phone()).isEqualTo("0987654321");
        assertThat(response.code()).isEqualTo("HN01");
    }

    @Test
    void updateStore_storeManager_differentStore_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest(null, "456 Nguyen Hue", null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateStore_storeManager_ownStore_partialUpdate_keepsUnspecifiedFieldsUnchanged() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.save(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateStore(storeId, new UpdateStoreRequest(null, "456 Nguyen Hue", null),
                principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.address()).isEqualTo("456 Nguyen Hue");
        assertThat(response.name()).isEqualTo("Chi nhanh Q1"); // giữ nguyên, không gửi field này
        assertThat(response.phone()).isEqualTo("0901234567"); // giữ nguyên
    }

    @Test
    void updateStore_archivedStore_throwsBusinessRuleViolation_RULE_03_06() {
        // RULE-03-06 (mở rộng 2026-09-17) — ARCHIVED là Terminal State, chặn UpdateStore trên
        // chính record Store, không chỉ dữ liệu cấu hình con.
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store archived = store(storeId, organizationId, "HN01");
        archived.setStatus(StoreStatus.ARCHIVED);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest("Ten moi", null, null),
                principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-06"));
        verify(storeRepository, never()).save(any());
    }

    @Test
    void updateStore_archivedStore_actorOutsideScope_stillDeniedByScopeNot400() {
        // Guard thẩm quyền phải chạy TRƯỚC guard ARCHIVED — actor ngoài scope nhận đúng 403,
        // không lỡ nhận nhầm 400 (cùng tinh thần AuthServiceImpl.createStaff).
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store archived = store(storeId, organizationId, "HN01");
        archived.setStatus(StoreStatus.ARCHIVED);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(archived));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest("Ten moi", null, null), actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void updateStore_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        when(storeRepository.save(any(Store.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Store.class, storeId));

        assertThatThrownBy(() -> service.updateStore(storeId, new UpdateStoreRequest("Ten moi", null, null),
                principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void listStores_organizationAdmin_differentOrg_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.listStores(organizationId, actor, pageable))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void listStores_orgNotFound_throwsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.existsById(organizationId)).thenReturn(false);
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.listStores(organizationId, principal(UserRole.SUPER_ADMIN, null), pageable))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listStores_scoped_returnsOnlyOrgStores() {
        UUID organizationId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(organizationRepository.existsById(organizationId)).thenReturn(true);
        when(storeRepository.findAllByOrganizationId(eq(organizationId), eq(pageable)))
                .thenReturn(new PageImpl<>(java.util.List.of(store(UUID.randomUUID(), organizationId, "HN01"))));

        PageResponse<?> page = service.listStores(organizationId, principal(UserRole.ORGANIZATION_ADMIN, organizationId), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
    }
}
