package com.petcare.module.catalog.service;

import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/catalog-v1.md C1 — ManageService/ViewService (RULE-05-03, RULE-05-06). Tên
 * "ServiceCatalogService" (không phải "ServiceService") để tránh trùng tên đôi khó đọc với
 * entity {@link com.petcare.module.catalog.entity.Service}.
 */
public interface ServiceCatalogService {

    ServiceResponse createService(CreateServiceRequest request, UserPrincipal actor);

    ServiceResponse updateService(UUID serviceId, UpdateServiceRequest request, UserPrincipal actor);

    ServiceResponse getService(UUID serviceId, UserPrincipal actor);

    PageResponse<ServiceResponse> listServices(UserPrincipal actor, Pageable pageable);

    /**
     * RULE-03-02 (ActivateStore, Module 03, điều kiện 3 — "danh mục dịch vụ khả dụng") — điểm
     * tích hợp cross-module để {@code StoreServiceImpl.activateStore} kiểm tra Organization có
     * ít nhất 1 {@link com.petcare.module.catalog.entity.Service} đang active hay chưa, KHÔNG
     * đi qua repository (CLAUDE.md: module không import entity/repository của module khác).
     */
    boolean hasActiveService(UUID organizationId);
}
