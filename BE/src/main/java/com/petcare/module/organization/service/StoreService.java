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

    /**
     * RULE-03-02/FSM-2 (docs/03-state-machines.md §2) — DRAFT/SUSPENDED/DEACTIVATED -> ACTIVE.
     * Idempotent: Store đã ACTIVE trả 200 no-op. Actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn
     * quyền) — không nhận STORE_MANAGER, khác {@link #updateStore}.
     */
    StoreResponse activateStore(UUID storeId, UserPrincipal actor);

    /**
     * RULE-03-04/FSM-2 (docs/03-state-machines.md §2) — chỉ từ ACTIVE -> SUSPENDED. Idempotent:
     * Store đã SUSPENDED trả 200 no-op. Actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn quyền) —
     * không nhận STORE_MANAGER, cùng {@link #activateStore}. Không có guard nghiệp vụ nào khác
     * ngoài FSM — hiệu ứng khóa booking/walk-in/order mới (RULE-03-04) do module Appointment/
     * Queue/Order enforce khi các module đó được triển khai, không thuộc contract Store này.
     */
    StoreResponse suspendStore(UUID storeId, UserPrincipal actor);

    /**
     * RULE-03-04/FSM-2 (docs/03-state-machines.md §2) — chỉ từ ACTIVE -> DEACTIVATED. Idempotent:
     * Store đã DEACTIVATED trả 200 no-op. Actor CHỈ OrganizationAdmin (SUPER_ADMIN toàn quyền) —
     * không nhận STORE_MANAGER, cùng {@link #activateStore}/{@link #suspendStore}. Không có guard
     * nghiệp vụ nào khác ngoài FSM — hiệu ứng khóa booking/walk-in/order mới (RULE-03-04) do module
     * Appointment/Queue/Order enforce khi các module đó được triển khai, không thuộc contract Store
     * này (cùng lý do {@link #suspendStore}).
     */
    StoreResponse deactivateStore(UUID storeId, UserPrincipal actor);

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
