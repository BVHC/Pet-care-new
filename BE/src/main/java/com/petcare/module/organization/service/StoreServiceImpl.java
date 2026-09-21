package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.mapper.StoreMapper;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
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

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — phần Store (CreateStore + đọc).
 * docs/02-business-rules.md RULE-03-01 (1 Org cha duy nhất). Activate/Suspend/
 * Deactivate/Archive/OperatingHours/StoreResource ngoài phạm vi lần triển khai này.
 */
@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreRepository storeRepository;
    private final OrganizationRepository organizationRepository;
    private final StoreMapper storeMapper;

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
            store = storeRepository.save(store);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Store có @Version (BaseEntity) — 2 request PATCH đồng thời trên cùng Store có thể
            // đụng version conflict thật; save() tường minh + catch ở đây thay vì dựa vào dirty-
            // checking tự flush ngầm lúc @Transactional commit (cùng lý do đã sửa ở
            // OrganizationServiceImpl.updateOrganization — không thì exception này không có chỗ
            // nào bắt được, rơi xuống handleGeneric -> 500 thay vì 409 CONCURRENCY_CONFLICT).
            throw new ConcurrencyConflictException("Store", storeId);
        }

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
