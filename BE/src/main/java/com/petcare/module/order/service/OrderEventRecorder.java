package com.petcare.module.order.service;

import com.petcare.module.order.entity.Order;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ghi Outbox event Module 14 — cùng pattern {@code InventoryEventRecorder}/
 * {@code ProcurementEventRecorder}. Payload theo docs/03-state-machines.md §5 domain event catalog
 * cho {@code OrderCreated}/{@code OrderCancelled}/{@code OrderTimedOut}/{@code OrderConfirmed}/
 * {@code OrderProcessed}/{@code ProductOrderPrepared}/{@code OrderDelivered} — 7 event có call site
 * trong phạm vi task này ({@code CheckoutOrder} chỉ refresh TTL, không đổi state, không có event
 * riêng theo đúng FSM table; {@code OrderCancelledWithRefund}/{@code OrderRefunded} ngoài phạm vi,
 * phụ thuộc Refund M17). Không có {@code @Transactional} riêng — join transaction đang mở của
 * caller.
 */
@Component
@RequiredArgsConstructor
class OrderEventRecorder {

    private final OutboxEventRepository outboxEventRepository;

    void recordOrderCreated(Order order) {
        record(order, "OrderCreated");
    }

    void recordOrderCancelled(Order order) {
        record(order, "OrderCancelled");
    }

    void recordOrderTimedOut(Order order) {
        record(order, "OrderTimedOut");
    }

    void recordOrderConfirmed(Order order) {
        record(order, "OrderConfirmed");
    }

    void recordOrderProcessed(Order order) {
        record(order, "OrderProcessed");
    }

    void recordProductOrderPrepared(Order order) {
        record(order, "ProductOrderPrepared");
    }

    void recordOrderDelivered(Order order) {
        record(order, "OrderDelivered");
    }

    private void record(Order order, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Order");
        event.setAggregateId(order.getId().toString());
        event.setEventType(eventType);
        event.setPayload("{\"orderId\":\"" + order.getId()
                + "\",\"storeId\":\"" + order.getStoreId()
                + "\",\"customerId\":\"" + order.getCustomerId()
                + "\",\"status\":\"" + order.getStatus().name()
                + "\"}");
        outboxEventRepository.save(event);
    }
}
