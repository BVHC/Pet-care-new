package com.petcare.module.inventory.service;

import com.petcare.module.inventory.dto.CountInventoryRequest;
import com.petcare.module.inventory.dto.CountInventoryResponse;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.IssueInventoryRequest;
import com.petcare.module.inventory.dto.ReceiveInventoryRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * docs/api/inventory-v1.md C1 — TrackInventory/ReceiveInventory/IssueInventory/CountInventory
 * (RULE-12-01, RULE-12-05). Rollup tổng tồn kho theo Store+Product — chi tiết theo lô (FEFO)
 * nằm ở {@link InventoryBatchService}.
 */
public interface InventoryItemService {

    PageResponse<InventoryItemResponse> trackInventory(UUID storeId, UUID productId, boolean lowOnly,
                                                         UserPrincipal actor, Pageable pageable);

    InventoryItemResponse receiveInventory(UUID storeId, ReceiveInventoryRequest request, UserPrincipal actor);

    InventoryItemResponse issueInventory(UUID storeId, IssueInventoryRequest request, UserPrincipal actor);

    CountInventoryResponse countInventory(UUID storeId, CountInventoryRequest request, UserPrincipal actor);
}
