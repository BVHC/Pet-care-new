package com.petcare.module.procurement.controller;

import com.petcare.module.procurement.dto.CreateSupplierRequest;
import com.petcare.module.procurement.dto.SupplierResponse;
import com.petcare.module.procurement.dto.UpdateSupplierRequest;
import com.petcare.module.procurement.service.SupplierService;
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
 * Module 13 — docs/api/procurement-v1.md (ManageSupplier, RULE-13-04). Organization Admin CRUD
 * thuần — không có SUPER_ADMIN (create suy ra organizationId từ actor.getOrganizationId(), SUPER_ADMIN
 * không gắn Organization — RULE-02-02, cùng lý do ProductController không cho SUPER_ADMIN ở POST).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class SupplierController {

    private final SupplierService supplierService;

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PostMapping("/api/suppliers")
    public ResponseEntity<ApiResponse<SupplierResponse>> createSupplier(
            @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateSupplierRequest request) {
        SupplierResponse response = supplierService.createSupplier(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @PatchMapping("/api/suppliers/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> updateSupplier(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(supplierService.updateSupplier(supplierId, request, actor)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @GetMapping("/api/suppliers/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> getSupplier(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID supplierId) {
        return ResponseEntity.ok(ApiResponse.ok(supplierService.getSupplier(supplierId, actor)));
    }

    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    @GetMapping("/api/suppliers")
    public ResponseEntity<ApiResponse<PageResponse<SupplierResponse>>> listSuppliers(
            @AuthenticationPrincipal UserPrincipal actor, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(supplierService.listSuppliers(actor, pageable)));
    }
}
