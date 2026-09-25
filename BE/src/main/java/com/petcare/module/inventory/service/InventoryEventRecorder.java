package com.petcare.module.inventory.service;

import com.petcare.module.inventory.entity.InventoryAdjustment;
import com.petcare.module.inventory.entity.InventoryItem;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.outbox.OutboxJsonSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ghi Outbox event Module 12 — cùng pattern {@code StoreEventRecorder} (module organization).
 * Payload field theo đúng docs/04-glossary.md §"Domain Event Catalog":
 * {@code InventoryAdjusted} (dòng 1310: adjustment_id/store_id/sku/delta_qty/reason/approved_by),
 * {@code LowStockAlertTriggered} (dòng 1312: store_id/sku/current_stock/reorder_threshold).
 * {@code InventoryAdjustmentRejected} không có trong bảng glossary (chỉ liệt kê nhánh
 * approve) — bổ sung đối xứng cho nhánh reject, cùng field shape với InventoryAdjusted trừ
 * {@code delta_qty} (reject không cập nhật sổ). Không có {@code @Transactional} riêng — join
 * transaction đang mở của caller.
 */
@Component
@RequiredArgsConstructor
class InventoryEventRecorder {

    private final OutboxEventRepository outboxEventRepository;

    void recordAdjustmentEvent(InventoryAdjustment adjustment, String sku, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("InventoryAdjustment");
        event.setAggregateId(adjustment.getId().toString());
        event.setEventType(eventType);
        event.setPayload(buildAdjustmentPayloadJson(adjustment, sku));
        outboxEventRepository.save(event);
    }

    /**
     * RULE-12-12 — TriggerLowStockAlert: không phải endpoint (docs/api/inventory-v1.md "Đóng
     * băng phạm vi": background job). Side-effect edge-triggered: chỉ ghi khi vừa CHUYỂN từ
     * "không thấp" sang "thấp" ({@code quantityAvailable <= minStockLevel}), tránh spam event
     * mỗi lần Receive/Issue/Approve khi tồn kho vẫn đang ở trạng thái thấp từ trước.
     */
    void maybeRecordLowStockAlert(InventoryItem item, int beforeAvailable, String sku) {
        boolean wasLow = beforeAvailable <= item.getMinStockLevel();
        if (!wasLow && item.isLowStock()) {
            OutboxEvent event = new OutboxEvent();
            event.setAggregateType("Inventory");
            event.setAggregateId(item.getId().toString());
            event.setEventType("LowStockAlertTriggered");
            event.setPayload(buildLowStockPayloadJson(item, sku));
            outboxEventRepository.save(event);
        }
    }

    private String buildAdjustmentPayloadJson(InventoryAdjustment adjustment, String sku) {
        return "{\"adjustmentId\":\"" + adjustment.getId()
                + "\",\"storeId\":\"" + adjustment.getStoreId()
                + "\",\"sku\":\"" + OutboxJsonSupport.escapeJson(sku)
                + "\",\"deltaQty\":" + adjustment.getQuantityAdjusted()
                + ",\"reason\":\"" + adjustment.getReason().name()
                + "\",\"approvedBy\":" + (adjustment.getApprovedBy() == null ? "null" : "\"" + adjustment.getApprovedBy() + "\"")
                + "}";
    }

    private String buildLowStockPayloadJson(InventoryItem item, String sku) {
        return "{\"storeId\":\"" + item.getStoreId()
                + "\",\"sku\":\"" + OutboxJsonSupport.escapeJson(sku)
                + "\",\"currentStock\":" + item.getQuantityAvailable()
                + ",\"reorderThreshold\":" + item.getMinStockLevel()
                + "}";
    }
}
