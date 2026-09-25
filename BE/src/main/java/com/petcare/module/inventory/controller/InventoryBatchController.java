package com.petcare.module.inventory.controller;

import com.petcare.module.inventory.dto.InventoryBatchResponse;
import com.petcare.module.inventory.service.InventoryBatchService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Module 12 — docs/api/inventory-v1.md §A5/C1 (TrackBatch/TrackExpiry, RULE-12-11 FEFO). */
@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
public class InventoryBatchController {

    private final InventoryBatchService inventoryBatchService;

    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ORGANIZATION_ADMIN') or hasRole('STORE_MANAGER') or hasRole('INVENTORY_STAFF')")
    @GetMapping("/api/stores/{id}/inventory-batches")
    public ResponseEntity<ApiResponse<PageResponse<InventoryBatchResponse>>> trackBatches(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable("id") UUID storeId,
            @RequestParam(value = "productId", required = false) UUID productId,
            @RequestParam(value = "expiredOnly", required = false, defaultValue = "false") boolean expiredOnly,
            @RequestParam(value = "expiringBefore", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiringBefore,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                inventoryBatchService.trackBatches(storeId, productId, expiredOnly, expiringBefore, actor, pageable)));
    }
}
