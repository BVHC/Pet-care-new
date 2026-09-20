package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreResourceRequest;
import com.petcare.module.organization.dto.StoreResourceListResponse;
import com.petcare.module.organization.dto.StoreResourceResponse;
import com.petcare.module.organization.dto.UpdateStoreResourceRequest;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.entity.StoreResource;
import com.petcare.module.organization.mapper.StoreResourceMapper;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.module.organization.repository.StoreResourceRepository;
import com.petcare.platform.audit.AuditResourceId;
import com.petcare.platform.audit.AuditStoreId;
import com.petcare.platform.audit.Auditable;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — ConfigureStoreResource (RULE-03-02/08).
 * Actor CHỈ STORE_MANAGER đúng Store mình quản lý (quyết định PO 2026-09-17, cùng logic đã chốt
 * cho ConfigureOperatingHour — bám literal docs/01-business-operations.md 01#3, không có ngoại
 * lệ SUPER_ADMIN/ORGANIZATION_ADMIN).
 */
@Service
@RequiredArgsConstructor
public class StoreResourceServiceImpl implements StoreResourceService {

    private final StoreRepository storeRepository;
    private final StoreResourceRepository storeResourceRepository;
    private final StoreResourceMapper storeResourceMapper;

    @Override
    @Transactional
    @Auditable(action = "ConfigureStoreResource", resourceType = "StoreResource")
    public StoreResourceResponse createResource(@AuditStoreId UUID storeId, CreateStoreResourceRequest request,
                                                 UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        RoleScopeGuard.assertIsOwnStoreManager(actor, storeId);

        // RULE-03-06 (gốc, Phase 4 — GAP-ORG-01): StoreResource là dữ liệu cấu hình con của Store,
        // trở thành bất biến chỉ đọc khi Store đã ARCHIVED — cùng áp dụng như OperatingHours.
        if (store.getStatus() == StoreStatus.ARCHIVED) {
            throw new BusinessRuleViolationException("RULE-03-06",
                    "Store đã lưu trữ (ARCHIVED), không được cấu hình tài nguyên vật tư");
        }

        // RULE-03-08 — check nghiệp vụ chính cho UK (store_id, resource_code); đường chính, tự đủ
        // cho mọi request bình thường (không đồng thời).
        if (storeResourceRepository.existsByStoreIdAndResourceCode(storeId, request.resourceCode())) {
            throw new BusinessRuleViolationException("RULE-03-08", "Mã tài nguyên đã tồn tại trong Store");
        }

        boolean active = request.isActive() == null || request.isActive();
        StoreResource resource = new StoreResource(storeId, request.resourceCode(), request.resourceName(),
                request.resourceType(), active);
        try {
            resource = storeResourceRepository.save(resource);
        } catch (DataIntegrityViolationException ex) {
            // RULE-03-08 — race condition giữa pre-check existsByStoreIdAndResourceCode() và save():
            // UNIQUE constraint uq_store_resources_code ở DB là guard thật; request thua ở đây nhận
            // lỗi nghiệp vụ sạch (400) thay vì 500, cùng pattern StoreServiceImpl.createStore.
            throw new BusinessRuleViolationException("RULE-03-08", "Mã tài nguyên đã tồn tại trong Store");
        }

        return storeResourceMapper.toResponse(resource);
    }

    @Override
    @Transactional
    @Auditable(action = "ConfigureStoreResource", resourceType = "StoreResource")
    public StoreResourceResponse updateResource(@AuditStoreId UUID storeId, @AuditResourceId UUID resourceId,
                                                 UpdateStoreResourceRequest request, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        RoleScopeGuard.assertIsOwnStoreManager(actor, storeId);

        // RULE-03-06 — cùng lý do createResource: StoreResource là dữ liệu cấu hình con, bất biến
        // chỉ đọc khi Store đã ARCHIVED.
        if (store.getStatus() == StoreStatus.ARCHIVED) {
            throw new BusinessRuleViolationException("RULE-03-06",
                    "Store đã lưu trữ (ARCHIVED), không được cấu hình tài nguyên vật tư");
        }

        StoreResource resource = storeResourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("StoreResource", resourceId));
        // Chặn IDOR xuyên Store: resourceId hợp lệ nhưng thuộc Store khác — coi như không tồn tại
        // trong phạm vi Store này (404, không phải 403 — không tiết lộ resource có tồn tại ở nơi khác).
        if (!resource.getStoreId().equals(storeId)) {
            throw new ResourceNotFoundException("StoreResource", resourceId);
        }

        if (request.resourceName() != null) {
            resource.setResourceName(request.resourceName());
        }
        if (request.isActive() != null) {
            resource.setActive(request.isActive());
        }

        resource = storeResourceRepository.save(resource);
        return storeResourceMapper.toResponse(resource);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreResourceListResponse listResources(UUID storeId, UserPrincipal actor) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store", storeId));
        RoleScopeGuard.assertCanManageStore(actor, store.getOrganizationId(), storeId);

        return storeResourceMapper.toListResponse(storeResourceRepository.findAllByStoreId(storeId));
    }
}
