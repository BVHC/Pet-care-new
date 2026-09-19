package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.CreateStoreRequest;
import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.dto.UpdateStoreRequest;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md §A/C2
 * (CreateStore + list/detail derived). RULE-03-01: mỗi Store thuộc đúng 1
 * Organization cha; cách ly tenant enforce ở Service qua RoleScopeGuard.
 * Không có StoreManager ở 3 endpoint này (khớp contract — chỉ OrgAdmin/SUPER_ADMIN).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class StoreController {

    private final StoreService storeService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/api/organizations/{organizationId}/stores")
    public ResponseEntity<ApiResponse<StoreResponse>> createStore(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("organizationId") UUID organizationId,
            @Valid @RequestBody CreateStoreRequest request) {
        StoreResponse response = storeService.createStore(organizationId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN')")
    @GetMapping("/api/organizations/{organizationId}/stores")
    public ResponseEntity<ApiResponse<PageResponse<StoreResponse>>> listStores(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("organizationId") UUID organizationId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.listStores(organizationId, actor, pageable)));
    }

    // GetStore mở cho cả STORE_MANAGER (chỉ Store mình quản lý, RoleScopeGuard#assertCanManageStore)
    // — trước đó chỉ SUPER_ADMIN/ORGANIZATION_ADMIN, bất nhất với UpdateStore/ConfigureOperatingHour/
    // ConfigureStoreResource vốn đều cho StoreManager gọi trên đúng Store của mình.
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/stores/{id}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getStore(storeId, actor)));
    }

    // UpdateStore — docs/api/org-store-v1.md B: OrgAdmin (mọi Store trong Org mình) +
    // StoreManager (chỉ Store mình quản lý), cùng 1 shape request (openapi #UpdateStoreRequest).
    // Phân biệt phạm vi ở Service qua RoleScopeGuard#assertCanManageStore, không phải ở đây.
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PatchMapping("/api/stores/{id}")
    public ResponseEntity<ApiResponse<StoreResponse>> updateStore(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody UpdateStoreRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.updateStore(storeId, request, actor)));
    }
}
