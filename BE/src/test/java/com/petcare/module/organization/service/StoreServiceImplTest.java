package com.petcare.module.organization.service;

import com.petcare.module.catalog.service.ServiceCatalogService;
import com.petcare.module.catalog.service.StoreOverrideService;
import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.module.organization.entity.OperatingHour;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.fsm.StoreTransitionHandler;
import com.petcare.module.organization.mapper.StoreMapper;
import com.petcare.module.organization.mapper.StoreMapperImpl;
import com.petcare.module.organization.repository.OperatingHourRepository;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.module.organization.repository.StoreResourceRepository;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-03-01, RULE-02-01/05 (cách ly tenant). */
@ExtendWith(MockitoExtension.class)
class StoreServiceImplTest {

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OperatingHourRepository operatingHourRepository;
    @Mock
    private StoreResourceRepository storeResourceRepository;
    @Mock
    private ServiceCatalogService serviceCatalogService;
    @Mock
    private StoreOverrideService storeOverrideService;
    @Mock
    private StoreEventRecorder storeEventRecorder;

    private final StoreMapper storeMapper = new StoreMapperImpl();
    // Instance thật (không mock) — muốn kiểm chứng luôn cả logic FSM thật khi test activateStore,
    // cùng cách storeMapper dùng StoreMapperImpl thật ở trên.
    private final StoreTransitionHandler storeTransitionHandler = new StoreTransitionHandler();

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
        service = new StoreServiceImpl(storeRepository, organizationRepository, storeMapper,
                storeTransitionHandler, operatingHourRepository, storeResourceRepository,
                serviceCatalogService, storeOverrideService, storeEventRecorder);
    }

    private static OperatingHour openHour(UUID storeId) {
        return new OperatingHour(storeId, 2, LocalTime.of(8, 0), LocalTime.of(18, 0), false);
    }

    private static OperatingHour closedHour(UUID storeId) {
        return new OperatingHour(storeId, 2, null, null, true);
    }

    /** Đủ điều kiện RULE-03-02 cho storeId — dùng trong test "thành công"/idempotent. */
    private void mockAllActivationGuardsSatisfied(UUID storeId, UUID organizationId) {
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId))
                .thenReturn(List.of(openHour(storeId)));
        when(storeResourceRepository.existsByStoreIdAndActiveTrue(storeId)).thenReturn(true);
        when(serviceCatalogService.hasActiveService(organizationId)).thenReturn(true);
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
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        verify(storeRepository, never()).saveAndFlush(any());
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
        when(storeRepository.saveAndFlush(any(Store.class)))
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

    // ---- activateStore (RULE-03-02, FSM-2) ----

    @Test
    void activateStore_notFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activateStore_actorOutsideScope_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.activateStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void activateStore_storeManager_ownStore_stillDeniedByScope() {
        // RULE-03-02/FSM-2 — actor CHỈ OrganizationAdmin, không nhận STORE_MANAGER dù đúng Store
        // mình quản lý (khác UpdateStore).
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.activateStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StoreStatus.class, names = {"DRAFT", "SUSPENDED", "DEACTIVATED"})
    void activateStore_organizationAdmin_ownOrg_fromValidSourceState_activates(StoreStatus from) {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(from);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        mockAllActivationGuardsSatisfied(storeId, organizationId);
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.ACTIVE);
        verify(storeOverrideService).initializeOverridesForStore(organizationId, storeId);
        verify(storeEventRecorder).record(existing, "StoreActivated");
    }

    @Test
    void activateStore_superAdmin_anyOrg_activates() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.DRAFT);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        mockAllActivationGuardsSatisfied(storeId, organizationId);
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.activateStore(storeId, principal(UserRole.SUPER_ADMIN, null));

        assertThat(response.status()).isEqualTo(StoreStatus.ACTIVE);
    }

    @Test
    void activateStore_alreadyActive_idempotent_noGuardOrSideEffectInteractions() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));

        var response = service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.ACTIVE);
        verify(storeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(operatingHourRepository, storeResourceRepository, serviceCatalogService,
                storeOverrideService, storeEventRecorder);
    }

    @Test
    void activateStore_missingOperatingHours_empty_throwsBusinessRuleViolation_RULE_03_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId)).thenReturn(List.of());
        // storeResourceRepository/serviceCatalogService KHÔNG được mock ở đây — guard operating
        // hours throw sớm nhất (thứ tự (1)->(2)->(3) trong StoreServiceImpl.activateStore), 2 mock
        // kia sẽ không bao giờ được gọi tới; mock thừa sẽ bị MockitoExtension strict-stub bắt lỗi.

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-02"));
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    void activateStore_operatingHoursAllClosed_throwsBusinessRuleViolation_RULE_03_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId))
                .thenReturn(List.of(closedHour(storeId)));

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-02"));
    }

    @Test
    void activateStore_noActiveResource_throwsBusinessRuleViolation_RULE_03_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId))
                .thenReturn(List.of(openHour(storeId)));
        when(storeResourceRepository.existsByStoreIdAndActiveTrue(storeId)).thenReturn(false);
        // serviceCatalogService KHÔNG mock — guard (2) throw trước khi guard (3) chạy tới.

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-02"));
    }

    @Test
    void activateStore_noActiveCatalogService_throwsBusinessRuleViolation_RULE_03_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        when(operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId))
                .thenReturn(List.of(openHour(storeId)));
        when(storeResourceRepository.existsByStoreIdAndActiveTrue(storeId)).thenReturn(true);
        when(serviceCatalogService.hasActiveService(organizationId)).thenReturn(false);

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-03-02"));
    }

    @Test
    void activateStore_archivedStore_throwsInvalidStateTransition() {
        // ARCHIVED không nằm trong {DRAFT,SUSPENDED,DEACTIVATED} — storeTransitionHandler tự chặn
        // (409), dù guard RULE-03-02 (400) đã chạy qua trước đó theo đúng thứ tự guard-trước-FSM.
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store archived = store(storeId, organizationId, "HN01");
        archived.setStatus(StoreStatus.ARCHIVED);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(archived));
        mockAllActivationGuardsSatisfied(storeId, organizationId);

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    void activateStore_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        mockAllActivationGuardsSatisfied(storeId, organizationId);
        when(storeRepository.saveAndFlush(any(Store.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Store.class, storeId));

        assertThatThrownBy(() -> service.activateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(ConcurrencyConflictException.class);
        verify(storeOverrideService, never()).initializeOverridesForStore(any(), any());
    }

    // ---- suspendStore (RULE-03-04, FSM-2) ----

    @Test
    void suspendStore_notFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspendStore(storeId, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suspendStore_actorOutsideScope_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.suspendStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void suspendStore_storeManager_ownStore_stillDeniedByScope() {
        // RULE-03-04/FSM-2 — actor CHỈ OrganizationAdmin, không nhận STORE_MANAGER dù đúng Store
        // mình quản lý (cùng activateStore).
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.suspendStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void suspendStore_organizationAdmin_ownOrg_fromActive_suspends() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.suspendStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
        verify(storeEventRecorder).record(existing, "StoreSuspended");
    }

    @Test
    void suspendStore_superAdmin_anyOrg_suspends() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.suspendStore(storeId, principal(UserRole.SUPER_ADMIN, null));

        assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
    }

    @Test
    void suspendStore_alreadySuspended_idempotent_noGuardOrSideEffectInteractions() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.SUSPENDED);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));

        var response = service.suspendStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
        verify(storeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(storeEventRecorder);
    }

    @ParameterizedTest
    @EnumSource(value = StoreStatus.class, names = {"DRAFT", "DEACTIVATED", "ARCHIVED"})
    void suspendStore_wrongSourceState_throwsInvalidStateTransition(StoreStatus from) {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(from);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.suspendStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    void suspendStore_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Store.class, storeId));

        assertThatThrownBy(() -> service.suspendStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(ConcurrencyConflictException.class);
        verify(storeEventRecorder, never()).record(any(), any());
    }

    // ---- deactivateStore (RULE-03-04, FSM-2) ----

    @Test
    void deactivateStore_notFound_throwsResourceNotFound() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateStore(storeId, principal(UserRole.SUPER_ADMIN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateStore_actorOutsideScope_deniedByScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID());

        assertThatThrownBy(() -> service.deactivateStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void deactivateStore_storeManager_ownStore_stillDeniedByScope() {
        // RULE-03-04/FSM-2 — actor CHỈ OrganizationAdmin, không nhận STORE_MANAGER dù đúng Store
        // mình quản lý (cùng activateStore/suspendStore).
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store(storeId, organizationId, "HN01")));
        UserPrincipal actor = principal(UserRole.STORE_MANAGER, organizationId, storeId);

        assertThatThrownBy(() -> service.deactivateStore(storeId, actor))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void deactivateStore_organizationAdmin_ownOrg_fromActive_deactivates() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.deactivateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.DEACTIVATED);
        verify(storeEventRecorder).record(existing, "StoreDeactivated");
    }

    @Test
    void deactivateStore_superAdmin_anyOrg_deactivates() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.deactivateStore(storeId, principal(UserRole.SUPER_ADMIN, null));

        assertThat(response.status()).isEqualTo(StoreStatus.DEACTIVATED);
    }

    @Test
    void deactivateStore_alreadyDeactivated_idempotent_noGuardOrSideEffectInteractions() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.DEACTIVATED);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));

        var response = service.deactivateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId));

        assertThat(response.status()).isEqualTo(StoreStatus.DEACTIVATED);
        verify(storeRepository, never()).saveAndFlush(any());
        verifyNoInteractions(storeEventRecorder);
    }

    @ParameterizedTest
    @EnumSource(value = StoreStatus.class, names = {"DRAFT", "SUSPENDED", "ARCHIVED"})
    void deactivateStore_wrongSourceState_throwsInvalidStateTransition(StoreStatus from) {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(from);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.deactivateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    void deactivateStore_concurrentModification_throwsConcurrencyConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Store existing = store(storeId, organizationId, "HN01");
        existing.setStatus(StoreStatus.ACTIVE);
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existing));
        when(storeRepository.saveAndFlush(any(Store.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Store.class, storeId));

        assertThatThrownBy(() -> service.deactivateStore(storeId, principal(UserRole.ORGANIZATION_ADMIN, organizationId)))
                .isInstanceOf(ConcurrencyConflictException.class);
        verify(storeEventRecorder, never()).record(any(), any());
    }
}
