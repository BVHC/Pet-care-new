package com.petcare.module.procurement.service;

import com.petcare.module.procurement.entity.PurchaseOrder;
import com.petcare.module.procurement.entity.PurchaseRequest;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.outbox.OutboxJsonSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Ghi Outbox event Module 13 — cùng pattern {@code InventoryEventRecorder} (Module 12). Payload
 * field theo đúng docs/04-glossary.md §"Domain Event Catalog" cho `PurchaseRequest*`/
 * `PurchaseOrderCreated` (request_id/store_id/items/estimated_cost/created_by,
 * submitted_by/submitted_at, approved_by/approved_at, rejected_by/rejection_reason,
 * cancelled_by/cancelled_at, po_id/request_id/supplier_id/items/total_amount). {@code items} rút
 * gọn thành số dòng (line count) thay vì mảng đầy đủ — payload chỉ cần đủ ngữ cảnh định danh, chi
 * tiết đầy đủ đã có ở chính bảng nghiệp vụ, cùng tinh thần "vài field đơn giản" của
 * {@code StoreEventRecorder}. {@code SupplierCreated}/{@code SupplierUpdated} KHÔNG có trong Domain
 * Event Catalog glossary — bổ sung đối xứng cho ManageSupplier CRUD, cùng tinh thần
 * {@code InventoryAdjustmentRejected} ở Module 12. Không có {@code @Transactional} riêng — join
 * transaction đang mở của caller.
 */
@Component
@RequiredArgsConstructor
class ProcurementEventRecorder {

    private final OutboxEventRepository outboxEventRepository;

    void recordPurchaseRequestCreated(PurchaseRequest request, int lineCount, String estimatedCost) {
        record("PurchaseRequest", request.getId(), "PurchaseRequestCreated",
                "{\"requestId\":\"" + request.getId()
                        + "\",\"storeId\":\"" + request.getStoreId()
                        + "\",\"items\":" + lineCount
                        + ",\"estimatedCost\":\"" + estimatedCost
                        + "\",\"createdBy\":\"" + request.getCreatedBy()
                        + "\"}");
    }

    void recordPurchaseRequestSubmitted(PurchaseRequest request) {
        record("PurchaseRequest", request.getId(), "PurchaseRequestSubmitted",
                "{\"requestId\":\"" + request.getId()
                        + "\",\"submittedBy\":\"" + request.getCreatedBy()
                        + "\",\"submittedAt\":\"" + request.getSubmittedAt()
                        + "\"}");
    }

    void recordPurchaseRequestApproved(PurchaseRequest request) {
        record("PurchaseRequest", request.getId(), "PurchaseRequestApproved",
                "{\"requestId\":\"" + request.getId()
                        + "\",\"approvedBy\":\"" + request.getApprovedBy()
                        + "\",\"approvedAt\":\"" + request.getDecidedAt()
                        + "\"}");
    }

    void recordPurchaseRequestRejected(PurchaseRequest request) {
        record("PurchaseRequest", request.getId(), "PurchaseRequestRejected",
                "{\"requestId\":\"" + request.getId()
                        + "\",\"rejectedBy\":\"" + request.getApprovedBy()
                        + "\",\"rejectionReason\":\"" + OutboxJsonSupport.escapeJson(request.getRejectionReason())
                        + "\"}");
    }

    void recordPurchaseRequestCancelled(PurchaseRequest request, UUID cancelledBy) {
        record("PurchaseRequest", request.getId(), "PurchaseRequestCancelled",
                "{\"requestId\":\"" + request.getId()
                        + "\",\"cancelledBy\":\"" + cancelledBy
                        + "\",\"cancelledAt\":\"" + request.getCancelledAt()
                        + "\"}");
    }

    void recordPurchaseOrderCreated(PurchaseOrder order, int lineCount) {
        record("PurchaseOrder", order.getId(), "PurchaseOrderCreated",
                "{\"poId\":\"" + order.getId()
                        + "\",\"requestId\":\"" + order.getPurchaseRequestId()
                        + "\",\"supplierId\":\"" + order.getSupplierId()
                        + "\",\"items\":" + lineCount
                        + ",\"totalAmount\":\"" + order.getTotalAmount()
                        + "\"}");
    }

    void recordSupplierCreated(Supplier supplier) {
        record("Supplier", supplier.getId(), "SupplierCreated",
                "{\"supplierId\":\"" + supplier.getId()
                        + "\",\"organizationId\":\"" + supplier.getOrganizationId()
                        + "\",\"code\":\"" + OutboxJsonSupport.escapeJson(supplier.getCode())
                        + "\"}");
    }

    void recordSupplierUpdated(Supplier supplier) {
        record("Supplier", supplier.getId(), "SupplierUpdated",
                "{\"supplierId\":\"" + supplier.getId()
                        + "\",\"organizationId\":\"" + supplier.getOrganizationId()
                        + "\",\"status\":\"" + supplier.getStatus().name()
                        + "\"}");
    }

    private void record(String aggregateType, UUID aggregateId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId.toString());
        event.setEventType(eventType);
        event.setPayload(payload);
        outboxEventRepository.save(event);
    }
}
