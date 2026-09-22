package com.petcare.module.organization.service;

import com.petcare.module.organization.entity.Store;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.outbox.OutboxJsonSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ghi Outbox event trên aggregate {@code Store} (docs/03-state-machines.md §2, cột Domain Event
 * — StoreActivated/StoreSuspended/StoreDeactivated/StoreArchived), cùng pattern
 * {@code AccountEventRecorder} (module auth). Chỉ dùng bởi {@link StoreServiceImpl}; không có
 * {@code @Transactional} riêng — join transaction đang mở của caller (xem plan "Đảm bảo
 * transaction").
 */
@Component
@RequiredArgsConstructor
class StoreEventRecorder {

    private final OutboxEventRepository outboxEventRepository;

    void record(Store store, String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Store");
        event.setAggregateId(store.getId().toString());
        event.setEventType(eventType);
        event.setPayload(buildPayloadJson(store));
        outboxEventRepository.save(event);
    }

    private String buildPayloadJson(Store store) {
        return "{\"storeId\":\"" + store.getId()
                + "\",\"organizationId\":\"" + store.getOrganizationId()
                + "\",\"status\":\"" + OutboxJsonSupport.escapeJson(store.getStatus().name()) + "\"}";
    }
}
