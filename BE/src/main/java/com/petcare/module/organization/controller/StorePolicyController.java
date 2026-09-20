package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.StorePolicyResponse;
import com.petcare.module.organization.dto.UpdateStorePolicyRequest;
import com.petcare.module.organization.service.StorePolicyService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 03 (Organization & Store Management) — docs/api/org-store-v1.md §A/§B (nửa Store của Q7,
 * ConfigureStorePolicy, RULE-03-10). GET mở cho cả SUPER_ADMIN/ORGANIZATION_ADMIN/STORE_MANAGER
 * (mirror StoreResourceController#listResources); PATCH CHỈ STORE_MANAGER — không ngoại lệ admin,
 * cùng OperatingHourController/StoreResourceController create-update (khác OrganizationPolicyController).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class StorePolicyController {

    private final StorePolicyService storePolicyService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @GetMapping("/api/stores/{id}/policy")
    public ResponseEntity<ApiResponse<StorePolicyResponse>> getPolicy(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId) {
        return ResponseEntity.ok(ApiResponse.ok(storePolicyService.getPolicy(storeId, actor)));
    }

    @PreAuthorize("hasRole('STORE_MANAGER')")
    @PatchMapping("/api/stores/{id}/policy")
    public ResponseEntity<ApiResponse<StorePolicyResponse>> updatePolicy(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody UpdateStorePolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storePolicyService.updatePolicy(storeId, request, actor)));
    }
}
