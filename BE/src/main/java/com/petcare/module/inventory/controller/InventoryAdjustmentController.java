package com.petcare.module.inventory.controller;

import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.dto.RejectInventoryAdjustmentRequest;
import com.petcare.module.inventory.service.InventoryAdjustmentService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 12 — docs/api/inventory-v1.md §A6-A9/C1 (AdjustInventory/ApproveInventoryAdjustment/
 * RejectInventoryAdjustment, RULE-12-02/03). Create/List mở cho 4 role vận hành kho (cùng
 * {@link InventoryController}); Approve/Reject CHỈ manager-tier trở lên — KHÔNG INVENTORY_STAFF,
 * đúng RULE-12-03 (checker phải là StoreManager/OrgAdmin, không tự duyệt phiếu chính role tạo).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class InventoryAdjustmentController {

    private final InventoryAdjustmentService inventoryAdjustmentService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/stores/{id}/inventory-adjustments")
    public ResponseEntity<ApiResponse<InventoryAdjustmentResponse>> createAdjustment(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody CreateInventoryAdjustmentRequest request) {
        InventoryAdjustmentResponse response = inventoryAdjustmentService.createAdjustment(storeId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @GetMapping("/api/stores/{id}/inventory-adjustments")
    public ResponseEntity<ApiResponse<PageResponse<InventoryAdjustmentResponse>>> listAdjustments(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @RequestParam(value = "status", required = false) InventoryAdjustmentStatus status, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryAdjustmentService.listAdjustments(storeId, status, actor, pageable)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PostMapping("/api/inventory-adjustments/{id}/approve")
    public ResponseEntity<ApiResponse<InventoryAdjustmentResponse>> approveAdjustment(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID adjustmentId) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryAdjustmentService.approveAdjustment(adjustmentId, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PostMapping("/api/inventory-adjustments/{id}/reject")
    public ResponseEntity<ApiResponse<InventoryAdjustmentResponse>> rejectAdjustment(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID adjustmentId,
            @Valid @RequestBody(required = false) RejectInventoryAdjustmentRequest request) {
        RejectInventoryAdjustmentRequest body = request == null ? new RejectInventoryAdjustmentRequest(null) : request;
        return ResponseEntity.ok(ApiResponse.ok(inventoryAdjustmentService.rejectAdjustment(adjustmentId, body, actor)));
    }
}
