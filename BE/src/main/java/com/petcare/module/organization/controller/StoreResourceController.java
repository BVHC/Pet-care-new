package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.CreateStoreResourceRequest;
import com.petcare.module.organization.dto.StoreResourceListResponse;
import com.petcare.module.organization.dto.StoreResourceResponse;
import com.petcare.module.organization.dto.UpdateStoreResourceRequest;
import com.petcare.module.organization.service.StoreResourceService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md §C3
 * (ConfigureStoreResource, RULE-03-02/06/08). Create/Update CHỈ STORE_MANAGER đúng Store mình
 * quản lý (quyết định PO 2026-09-17, xem RoleScopeGuard#assertIsOwnStoreManager) — khác các
 * endpoint Store khác vốn cho cả SUPER_ADMIN/ORGANIZATION_ADMIN. List mở cho cả 3 role (đọc
 * không phải quyết định PO, dùng RoleScopeGuard#assertCanManageStore như các GET khác).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class StoreResourceController {

    private final StoreResourceService storeResourceService;

    @PreAuthorize("hasRole('STORE_MANAGER')")
    @PostMapping("/api/stores/{id}/resources")
    public ResponseEntity<ApiResponse<StoreResourceResponse>> createResource(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody CreateStoreResourceRequest request) {
        StoreResourceResponse response = storeResourceService.createResource(storeId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('STORE_MANAGER')")
    @PatchMapping("/api/stores/{id}/resources/{rid}")
    public ResponseEntity<ApiResponse<StoreResourceResponse>> updateResource(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @PathVariable("rid") UUID resourceId, @Valid @RequestBody UpdateStoreResourceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storeResourceService.updateResource(storeId, resourceId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/stores/{id}/resources")
    public ResponseEntity<ApiResponse<StoreResourceListResponse>> listResources(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId) {
        return ResponseEntity.ok(ApiResponse.ok(storeResourceService.listResources(storeId, actor)));
    }
}
