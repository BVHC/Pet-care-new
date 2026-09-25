package com.petcare.module.inventory.controller;

import com.petcare.module.inventory.dto.CountInventoryRequest;
import com.petcare.module.inventory.dto.CountInventoryResponse;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.IssueInventoryRequest;
import com.petcare.module.inventory.dto.ReceiveInventoryRequest;
import com.petcare.module.inventory.service.InventoryItemService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
 * Module 12 (Inventory) — docs/api/inventory-v1.md §A1-A4/C1 (TrackInventory/ReceiveInventory/
 * IssueInventory/CountInventory, RULE-12-01/02/05). 4 role vận hành kho theo
 * {@code RoleScopeGuard#assertCanOperateStoreInventory} — InventoryStaff là actor chính
 * (docs/01-business-operations.md §12), SUPER_ADMIN/ORGANIZATION_ADMIN/STORE_MANAGER toàn
 * quyền theo scope của mình.
 */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class InventoryController {

    private final InventoryItemService inventoryItemService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @GetMapping("/api/stores/{id}/inventory")
    public ResponseEntity<ApiResponse<PageResponse<InventoryItemResponse>>> trackInventory(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @RequestParam(value = "productId", required = false) UUID productId,
            @RequestParam(value = "lowOnly", required = false, defaultValue = "false") boolean lowOnly,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                inventoryItemService.trackInventory(storeId, productId, lowOnly, actor, pageable)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/stores/{id}/inventory/receive")
    public ResponseEntity<ApiResponse<InventoryItemResponse>> receiveInventory(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody ReceiveInventoryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryItemService.receiveInventory(storeId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/stores/{id}/inventory/issue")
    public ResponseEntity<ApiResponse<InventoryItemResponse>> issueInventory(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody IssueInventoryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryItemService.issueInventory(storeId, request, actor)));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @PostMapping("/api/stores/{id}/inventory/count")
    public ResponseEntity<ApiResponse<CountInventoryResponse>> countInventory(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @Valid @RequestBody CountInventoryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryItemService.countInventory(storeId, request, actor)));
    }
}
