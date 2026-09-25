package com.petcare.module.inventory.service;

import com.petcare.module.inventory.dto.CountInventoryRequest;
import com.petcare.module.inventory.dto.CountInventoryResponse;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.IssueInventoryRequest;
import com.petcare.module.inventory.dto.ReceiveInventoryRequest;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
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

    /**
     * Cross-module (Module 14 Order, RULE-14-04) — giữ chỗ tồn kho 15 phút cho đơn Online lúc
     * CreateOrder/CheckoutOrder: {@code quantityReserved += quantity}, {@code quantityAvailable -=
     * quantity}. Không qua actor guard — caller (Order) đã tự authorize ở tầng của mình. Ném
     * {@code BusinessRuleViolationException("RULE-14-02", ...)} nếu không đủ
     * {@code quantityAvailable}; {@code ConcurrencyConflictException} nếu optimistic-lock xung đột.
     */
    void reserveStock(UUID storeId, UUID productId, int quantity, UUID orderId, LocalDateTime expiresAt);

    /**
     * Cross-module (Module 14 Order) — nhả toàn bộ reservation {@code HELD} của 1 Order
     * (CancelOrder/ProcessOrderTimeout): {@code quantityReserved -= quantity},
     * {@code quantityAvailable += quantity}, reservation chuyển {@code RELEASED}. Idempotent —
     * không có reservation {@code HELD} nào thì no-op, không ném lỗi (an toàn khi CancelOrder và
     * ProcessOrderTimeout race nhau).
     */
    void releaseReservation(UUID orderId);

    /**
     * Cross-module (Module 14 Order, RULE-14-03/04) — trừ {@code quantityPhysical} trực tiếp cho
     * đơn POS (không qua giữ chỗ, khớp D-03 in-store instant handover). Không qua actor guard —
     * caller (Order) đã tự authorize ở tầng của mình bằng
     * {@code RoleScopeGuard#assertCanOperateStoreOrder} (Receptionist), khác actor của
     * {@link #issueInventory} ({@code assertCanOperateStoreInventory}, không có RECEPTIONIST) —
     * đây là lý do tách method riêng thay vì tái dùng {@code issueInventory} thẳng.
     */
    void deductPhysicalForOrder(UUID storeId, UUID productId, int quantity);
}
