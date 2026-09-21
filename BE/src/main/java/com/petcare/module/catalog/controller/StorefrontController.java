package com.petcare.module.catalog.controller;

import com.petcare.module.catalog.dto.EffectiveProductResponse;
import com.petcare.module.catalog.dto.EffectiveServiceResponse;
import com.petcare.module.catalog.service.StorefrontService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 05 — docs/api/catalog-v1.md §A/C3 (ViewProduct/ViewService storefront, RULE-05-06).
 * CUSTOMER được thêm vào (ngoài 3 role staff) — đây là 2 endpoint duy nhất của Catalog module
 * Customer được gọi trực tiếp.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class StorefrontController {

    private final StorefrontService storefrontService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('CUSTOMER')")
    @GetMapping("/api/stores/{id}/products")
    public ResponseEntity<ApiResponse<PageResponse<EffectiveProductResponse>>> listStoreProducts(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(storefrontService.listStoreProducts(storeId, actor, activeOnly, pageable)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('CUSTOMER')")
    @GetMapping("/api/stores/{id}/services")
    public ResponseEntity<ApiResponse<PageResponse<EffectiveServiceResponse>>> listStoreServices(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(storefrontService.listStoreServices(storeId, actor, activeOnly, pageable)));
    }
}
