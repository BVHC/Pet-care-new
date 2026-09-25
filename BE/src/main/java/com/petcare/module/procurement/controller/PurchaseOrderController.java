package com.petcare.module.procurement.controller;

import com.petcare.module.procurement.dto.CreatePurchaseOrderRequest;
import com.petcare.module.procurement.dto.PurchaseOrderResponse;
import com.petcare.module.procurement.service.PurchaseOrderService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module 13 — docs/api/procurement-v1.md C2 (CreatePurchaseOrder/TrackPurchaseOrder, RULE-13-04). */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/purchase-orders")
    public ResponseEntity<ApiResponse<PurchaseOrderResponse>> createPurchaseOrder(
            @AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreatePurchaseOrderRequest request) {
        PurchaseOrderResponse response = purchaseOrderService.createPurchaseOrder(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response, "success"));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @GetMapping("/api/purchase-orders/{id}")
    public ResponseEntity<ApiResponse<PurchaseOrderResponse>> getPurchaseOrder(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID orderId) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseOrderService.getPurchaseOrder(orderId, actor)));
    }
}
