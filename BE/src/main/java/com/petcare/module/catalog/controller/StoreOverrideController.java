package com.petcare.module.catalog.controller;

import com.petcare.module.catalog.dto.AvailabilityRequest;
import com.petcare.module.catalog.dto.AvailabilityResponse;
import com.petcare.module.catalog.dto.PriceRequest;
import com.petcare.module.catalog.dto.PriceResponse;
import com.petcare.module.catalog.service.StoreOverrideService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 05 — docs/api/catalog-v1.md §A/C2 (ConfigureProductPrice/ConfigureServicePrice/
 * ConfigureServiceAvailability). SUPER_ADMIN/ORGANIZATION_ADMIN/STORE_MANAGER đều được gọi
 * (contract ASSUMPTION A2) — phân biệt phạm vi ở Service qua RoleScopeGuard#assertCanManageStore.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class StoreOverrideController {

    private final StoreOverrideService storeOverrideService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PutMapping("/api/stores/{id}/products/{pid}/price")
    public ResponseEntity<ApiResponse<PriceResponse>> configureProductPrice(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("id") UUID storeId, @PathVariable("pid") UUID productId,
            @Valid @RequestBody PriceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storeOverrideService.configureProductPrice(storeId, productId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PutMapping("/api/stores/{id}/services/{sid}/price")
    public ResponseEntity<ApiResponse<PriceResponse>> configureServicePrice(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("id") UUID storeId, @PathVariable("sid") UUID serviceId,
            @Valid @RequestBody PriceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storeOverrideService.configureServicePrice(storeId, serviceId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PutMapping("/api/stores/{id}/services/{sid}/availability")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> configureServiceAvailability(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable("id") UUID storeId, @PathVariable("sid") UUID serviceId,
            @Valid @RequestBody AvailabilityRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storeOverrideService.configureServiceAvailability(storeId, serviceId, request, actor)));
    }
}
