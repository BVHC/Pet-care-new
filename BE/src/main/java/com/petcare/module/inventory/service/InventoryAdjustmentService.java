package com.petcare.module.inventory.service;

import com.petcare.module.inventory.dto.CreateInventoryAdjustmentRequest;
import com.petcare.module.inventory.dto.InventoryAdjustmentResponse;
import com.petcare.module.inventory.dto.RejectInventoryAdjustmentRequest;
import com.petcare.platform.enums.InventoryAdjustmentStatus;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/inventory-v1.md C1 — AdjustInventory/ApproveInventoryAdjustment/
 * RejectInventoryAdjustment (RULE-12-02, RULE-12-03 Maker-Checker). Cũng dùng bởi
 * {@code InventoryItemServiceImpl#countInventory} để tạo phiếu {@code COUNT_VARIANCE} khi kiểm
 * kê lệch số.
 */
public interface InventoryAdjustmentService {

    InventoryAdjustmentResponse createAdjustment(UUID storeId, CreateInventoryAdjustmentRequest request, UserPrincipal actor);

    InventoryAdjustmentResponse approveAdjustment(UUID adjustmentId, UserPrincipal actor);

    InventoryAdjustmentResponse rejectAdjustment(UUID adjustmentId, RejectInventoryAdjustmentRequest request, UserPrincipal actor);

    PageResponse<InventoryAdjustmentResponse> listAdjustments(UUID storeId, InventoryAdjustmentStatus status,
                                                               UserPrincipal actor, Pageable pageable);
}
