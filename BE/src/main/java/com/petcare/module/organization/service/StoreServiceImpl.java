package com.petcare.module.organization.service;

import com.petcare.module.catalog.service.ServiceCatalogService;
import com.petcare.module.catalog.service.StoreOverrideService;
import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.module.organization.entity.OperatingHour;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.fsm.StoreTransitionHandler;
import com.petcare.module.organization.mapper.StoreMapper;
import com.petcare.module.organization.repository.OperatingHourRepository;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.module.organization.repository.StoreResourceRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — phần Store (CreateStore + đọc + ActivateStore +
 * SuspendStore + DeactivateStore). docs/02-business-rules.md RULE-03-01 (1 Org cha duy nhất).
 * Archive/OperatingHours/StoreResource (CRUD) ngoài phạm vi lần triển khai này.
 */
@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreRepository storeRepository;
    private final OrganizationRepository organizationRepository;
    private final StoreMapper storeMapper;
    private final StoreTransitionHandler storeTransitionHandler;
    private final OperatingHourRepository operatingHourRepository;
    private final StoreResourceRepository storeResourceRepository;
    private final ServiceCatalogService serviceCatalogService;
    private final StoreOverrideService storeOverrideService;
    private final StoreEventRecorder storeEventRecorder;

    @Override
    @Transactional
    @Auditable(action = "CreateStore", resourceType = "Store")
    public StoreResponse createStore(UUID organizationId, CreateStoreRequest request, UserPrincipal actor) {
        // RULE-02-05 — actor phải quản trị đúng Organization cha này (path param, biết trước
        // khi đọc DB), cùng thứ tự guard-trước-tồn-tại như OrganizationServiceImpl.updateOrganization.
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException("Organization", organizationId);
        }

        // RULE-03-01 — check nghiệp vụ chính cho UK (organization_id, code); đường chính, tự đủ
        // cho mọi request bình thường (không đồng thời).
        if (storeRepository.existsByOrganizationIdAndCode(organizationId, request.code())) {
            throw new BusinessRuleViolationException("RULE-03-01", "Mã chi nhánh đã tồn tại trong Organization");
        }

        Store store = new Store(organizationId, request.code(), request.name(), request.facilityType(),
                request.address(), request.phone());
        try {
            store = storeRepository.save(store);
        } catch (DataIntegrityViolationException ex) {
            // RULE-03-01 — race condition giữa pre-check existsByOrganizationIdAndCode() và save():
            // 2 request tạo Store cùng (organizationId, code) gần như đồng thời đều có thể thấy
            // "chưa tồn tại" ở check trên. UNIQUE constraint uq_stores_org_code ở DB là guard thật;
            // request thua ở đây nhận lỗi nghiệp vụ sạch (400) thay vì 500 — cùng pattern
            // OrganizationServiceImpl.createOrganization.
            throw new BusinessRuleViolationException("RULE-03-01", "Mã chi nhánh đã tồn tại trong Organization");
        }

        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional
    @Auditable(action = "UpdateStore", resourceType = "Store")
    public StoreResponse updateStore(@AuditResourceId UUID storeId, UpdateStoreRequest request, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        // RULE-02-05 — org id/store id HIỆN TẠI của Store (không đổi qua UpdateStore, xem
        // UpdateStoreRequest — không có organizationId/code/facilityType để rebind).
        RoleScopeGuard.assertCanManageStore(actor, store.getOrganizationId(), store.getId());

        // RULE-03-06 (mở rộng 2026-09-17, xem Decision Log docs/02-business-rules.md mục 03) —
        // ARCHIVED là Terminal State tuyệt đối (FSM-2 Technical Invariant #4); GAP-ORG-01 (Phase 4)
        // ban đầu chỉ khóa dữ liệu cấu hình con, nay mở rộng sang chính record Store — kiểm tra SAU
        // assertCanManageStore để actor ngoài scope luôn nhận đúng 403 thay vì lỡ nhận nhầm 400.
        if (store.getStatus() == StoreStatus.ARCHIVED) {
            throw new BusinessRuleViolationException("RULE-03-06",
                    "Store đã lưu trữ (ARCHIVED), không được sửa thông tin");
        }

        if (request.name() != null) {
            store.setName(request.name());
        }
        if (request.address() != null) {
            store.setAddress(request.address());
        }
        if (request.phone() != null) {
            store.setPhone(request.phone());
        }

        try {
            // saveAndFlush (không phải save) — Store có @Version (BaseEntity), 2 request PATCH
            // đồng thời trên cùng Store có thể đụng version conflict thật. save()/merge() trên 1
            // entity đã managed KHÔNG ép flush ngay (Hibernate hoãn tới lúc @Transactional commit,
            // NGOÀI try/catch này) — nên ObjectOptimisticLockingFailureException không có chỗ nào
            // bắt được, rơi xuống handleGeneric -> 500 thay vì 409 CONCURRENCY_CONFLICT. saveAndFlush
            // ép flush ngay tại đây, cùng pattern đã hoạt động đúng ở PetServiceImpl.update/
            // managePetOwnership.
            store = storeRepository.saveAndFlush(store);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Store", storeId);
        }

        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional
    @Auditable(action = "ActivateStore", resourceType = "Store")
    public StoreResponse activateStore(@AuditResourceId UUID storeId, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        // RULE-03-02/FSM-2 — actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn quyền), KHÔNG dùng
        // assertCanManageStore (method đó nhận cả STORE_MANAGER, sai actor theo contract/FSM).
        RoleScopeGuard.assertCanManageOrganization(actor, store.getOrganizationId());

        // Idempotent (docs/api/org-store-v1.md B — "đã ACTIVE vẫn 200") — StateMachineBase không
        // có self-loop nên phải short-circuit tường minh, TRƯỚC guard RULE-03-02, để retry không
        // re-check/re-tạo gì thêm (xem plan "Đảm bảo transaction").
        if (store.getStatus() == StoreStatus.ACTIVE) {
            return storeMapper.toResponse(store);
        }

        // RULE-03-02 — áp dụng đồng nhất cho cả 3 trạng thái nguồn (DRAFT/SUSPENDED/DEACTIVATED),
        // chạy TRƯỚC storeTransitionHandler.validateTransition() (business guard trước FSM guard,
        // docs/convention/backend/05-fsm-pattern.md).
        List<OperatingHour> hours = operatingHourRepository.findAllByStoreIdOrderByDayOfWeek(storeId);
        // ASSUMPTION: "hợp lệ" = có cấu hình VÀ không phải toàn bộ ngày đều đóng cửa — RULE-03-02
        // không định nghĩa chi tiết hơn; ConfigureOperatingHour đã validate openTime<closeTime ở
        // ghi, nên chỉ cần kiểm tra có ít nhất 1 ngày mở cửa ở đây.
        if (hours.isEmpty() || hours.stream().allMatch(OperatingHour::isClosed)) {
            throw new BusinessRuleViolationException("RULE-03-02",
                    "Store chưa cấu hình giờ hoạt động hợp lệ (OperatingHours)");
        }
        if (!storeResourceRepository.existsByStoreIdAndActiveTrue(storeId)) {
            throw new BusinessRuleViolationException("RULE-03-02",
                    "Store chưa có tài nguyên cơ sở vật chất đang active (StoreResource)");
        }
        if (!serviceCatalogService.hasActiveService(store.getOrganizationId())) {
            throw new BusinessRuleViolationException("RULE-03-02",
                    "Organization chưa có dịch vụ khả dụng trong danh mục (StoreServiceCatalog)");
        }

        storeTransitionHandler.validateTransition(store.getStatus(), StoreStatus.ACTIVE);

        store.setStatus(StoreStatus.ACTIVE);
        try {
            // saveAndFlush — cùng lý do updateStore (xem comment ở đó): save() thường không ép
            // flush ngay nên ObjectOptimisticLockingFailureException thoát khỏi try/catch này.
            store = storeRepository.saveAndFlush(store);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Store", storeId);
        }

        // Điểm tích hợp cross-module CONFIRMED (StoreOverrideService#initializeOverridesForStore
        // javadoc) — khởi tạo override giá/khả dụng kế thừa từ Organization, cùng transaction.
        storeOverrideService.initializeOverridesForStore(store.getOrganizationId(), storeId);
        storeEventRecorder.record(store, "StoreActivated");

        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional
    @Auditable(action = "SuspendStore", resourceType = "Store")
    public StoreResponse suspendStore(@AuditResourceId UUID storeId, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        // RULE-03-04/FSM-2 — actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn quyền), cùng
        // activateStore, KHÔNG dùng assertCanManageStore.
        RoleScopeGuard.assertCanManageOrganization(actor, store.getOrganizationId());

        // Idempotent (docs/api/org-store-v1.md C2 — "đã suspended vẫn 200") — short-circuit
        // TRƯỚC storeTransitionHandler.validateTransition(), cùng lý do activateStore: retry
        // không re-check/re-ghi event gì thêm.
        if (store.getStatus() == StoreStatus.SUSPENDED) {
            return storeMapper.toResponse(store);
        }

        // Không có guard nghiệp vụ RULE-ID nào khác cho suspend (khác activateStore/RULE-03-02)
        // — contract docs/api/org-store-v1.md C2 xác nhận "Guards: chỉ từ ACTIVE (FSM-2)".
        storeTransitionHandler.validateTransition(store.getStatus(), StoreStatus.SUSPENDED);

        store.setStatus(StoreStatus.SUSPENDED);
        try {
            // saveAndFlush — cùng lý do updateStore/activateStore (xem comment ở updateStore).
            store = storeRepository.saveAndFlush(store);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Store", storeId);
        }

        storeEventRecorder.record(store, "StoreSuspended");

        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional
    @Auditable(action = "DeactivateStore", resourceType = "Store")
    public StoreResponse deactivateStore(@AuditResourceId UUID storeId, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        // RULE-03-04/FSM-2 — actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn quyền), cùng
        // activateStore/suspendStore, KHÔNG dùng assertCanManageStore.
        RoleScopeGuard.assertCanManageOrganization(actor, store.getOrganizationId());

        // Idempotent (docs/api/org-store-v1.md C2 — "đã deactivated vẫn 200") — short-circuit
        // TRƯỚC storeTransitionHandler.validateTransition(), cùng lý do activateStore/suspendStore:
        // retry không re-check/re-ghi event gì thêm.
        if (store.getStatus() == StoreStatus.DEACTIVATED) {
            return storeMapper.toResponse(store);
        }

        // Không có guard nghiệp vụ RULE-ID nào khác cho deactivate (khác activateStore/RULE-03-02)
        // — contract docs/api/org-store-v1.md C2 xác nhận "Guards: chỉ từ ACTIVE (FSM-2)", cùng
        // suspendStore.
        storeTransitionHandler.validateTransition(store.getStatus(), StoreStatus.DEACTIVATED);

        store.setStatus(StoreStatus.DEACTIVATED);
        try {
            // saveAndFlush — cùng lý do updateStore/activateStore/suspendStore.
            store = storeRepository.saveAndFlush(store);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Store", storeId);
        }

        storeEventRecorder.record(store, "StoreDeactivated");

        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreResponse getStore(UUID storeId, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        // Bug fix: trước đây dùng assertCanManageOrganization (chỉ SUPER_ADMIN/ORGANIZATION_ADMIN),
        // khiến STORE_MANAGER sửa được Store của mình (UpdateStore) nhưng không xem được. Đổi sang
        // assertCanManageStore để nhất quán với UpdateStore/ConfigureOperatingHour/ConfigureStoreResource.
        RoleScopeGuard.assertCanManageStore(actor, store.getOrganizationId(), store.getId());
        return storeMapper.toResponse(store);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StoreResponse> listStores(UUID organizationId, UserPrincipal actor, Pageable pageable) {
        RoleScopeGuard.assertCanManageOrganization(actor, organizationId);

        if (!organizationRepository.existsById(organizationId)) {
            throw new ResourceNotFoundException("Organization", organizationId);
        }

        Page<StoreResponse> page = storeRepository.findAllByOrganizationId(organizationId, pageable)
                .map(storeMapper::toResponse);
        return PageResponse.of(page);
    }

    @Override
    @Transactional(readOnly = true)
    public UUID getOrganizationIdForStore(UUID storeId) {
        return storeRepository.findById(storeId)
                .map(Store::getOrganizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
    }
}
