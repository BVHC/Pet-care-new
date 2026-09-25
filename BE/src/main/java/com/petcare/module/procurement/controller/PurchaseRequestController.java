package com.petcare.module.procurement.controller;

import com.petcare.module.procurement.dto.CreatePurchaseRequestRequest;
import com.petcare.module.procurement.dto.PurchaseRequestResponse;
import com.petcare.module.procurement.dto.RejectPurchaseRequestRequest;
import com.petcare.module.procurement.service.PurchaseRequestService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module 13 — docs/api/procurement-v1.md C1 (CreatePurchaseRequest/SubmitPurchaseRequest/
 * Approve-RejectPurchaseRequest/CancelPurchaseRequest, RULE-13-01→03, FSM-12). Create/Submit/Cancel
 * mở cho 4 role vận hành kho (cùng {@code InventoryController}); Approve/Reject CHỈ manager-tier
 * trở lên — KHÔNG INVENTORY_STAFF, đúng RULE-13-02 (checker phải là StoreManager/OrgAdmin).
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class PurchaseRequestController {

    private final PurchaseRequestService purchaseRequestService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/stores/{id}/purchase-requests")
    public ResponseEntity<ApiResponse<PurchaseRequestResponse>> createPurchaseRequest(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody CreatePurchaseRequestRequest request) {
        PurchaseRequestResponse response = purchaseRequestService.createPurchaseRequest(storeId, request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/purchase-requests/{id}/submit")
    public ResponseEntity<ApiResponse<PurchaseRequestResponse>> submitPurchaseRequest(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID requestId) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseRequestService.submitPurchaseRequest(requestId, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PostMapping("/api/purchase-requests/{id}/approve")
    public ResponseEntity<ApiResponse<PurchaseRequestResponse>> approvePurchaseRequest(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID requestId) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseRequestService.approvePurchaseRequest(requestId, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER')")
    @PostMapping("/api/purchase-requests/{id}/reject")
    public ResponseEntity<ApiResponse<PurchaseRequestResponse>> rejectPurchaseRequest(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID requestId,
            @Valid @RequestBody RejectPurchaseRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseRequestService.rejectPurchaseRequest(requestId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/purchase-requests/{id}/cancel")
    public ResponseEntity<ApiResponse<PurchaseRequestResponse>> cancelPurchaseRequest(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID requestId) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseRequestService.cancelPurchaseRequest(requestId, actor)));
    }
}
