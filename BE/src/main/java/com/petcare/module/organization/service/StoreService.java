package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface StoreService {

    StoreResponse createStore(UUID organizationId, CreateStoreRequest request, UserPrincipal actor);

    StoreResponse updateStore(UUID storeId, UpdateStoreRequest request, UserPrincipal actor);

    StoreResponse getStore(UUID storeId, UserPrincipal actor);

    PageResponse<StoreResponse> listStores(UUID organizationId, UserPrincipal actor, Pageable pageable);

    /**
     * Tra cứu organizationId của Store cho module khác cần biết Store thuộc Org nào mà KHÔNG áp
     * guard "quản trị Store" của {@link #getStore} (vd Module 05 Catalog RULE-05-06: Customer cần
     * xem storefront tại Store nhưng {@code RoleScopeGuard#assertCanManageStore} không nhận role
     * CUSTOMER). Không kiểm tra actor — caller tự chịu trách nhiệm authorization theo ngữ cảnh
     * riêng của module gọi.
     */
    UUID getOrganizationIdForStore(UUID storeId);
}
