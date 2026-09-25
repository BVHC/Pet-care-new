package com.petcare.module.inventory.service;

import com.petcare.module.inventory.dto.InventoryBatchResponse;
import com.petcare.module.inventory.entity.InventoryBatch;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * docs/api/inventory-v1.md C1 — {@code GET /stores/{id}/inventory-batches} (TrackBatch/
 * TrackExpiry, RULE-12-11). {@code receiveBatch}/{@code issueFefo} dùng nội bộ bởi
 * {@code InventoryItemServiceImpl} trong cùng transaction — actor-guard đã chạy ở caller trước
 * khi gọi, 2 method này không tự guard lại.
 */
public interface InventoryBatchService {

    PageResponse<InventoryBatchResponse> trackBatches(UUID storeId, UUID productId, boolean expiredOnly,
                                                        LocalDate expiringBefore, UserPrincipal actor, Pageable pageable);

    /** Upsert theo unique key (storeId, productId, batchNumber) — lặp lại cùng lô tự cộng dồn quantity. */
    InventoryBatch receiveBatch(UUID storeId, UUID productId, String batchNumber,
                                 LocalDate manufactureDate, LocalDate expiryDate, int quantity);

    /**
     * RULE-12-11 (FEFO) — tiêu thụ theo thứ tự hết hạn sớm nhất trước, bỏ qua lô đã hết hạn.
     * Ném {@code BusinessRuleViolationException("RULE-12-05", ...)} nếu tổng số lượng các lô còn
     * hạn không đủ {@code quantity} (kể cả khi rollup {@code InventoryItem} còn số).
     */
    void issueFefo(UUID storeId, UUID productId, int quantity);
}
