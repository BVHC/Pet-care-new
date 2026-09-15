package com.petcare.module.auth.service;

import com.petcare.module.auth.entity.Account;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.outbox.OutboxJsonSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Trích từ {@code AuthServiceImpl} — dùng chung bởi {@link AuthServiceImpl} và
 * {@link AccountLifecycleServiceImpl}, cả 2 đều cần ghi Outbox event trên
 * aggregate {@code Account} (docs/convention/backend cho Transactional
 * Outbox Pattern), tránh copy 2 lần logic giống hệt nhau.
 */
@Component
@RequiredArgsConstructor
class AccountEventRecorder {

    private final OutboxEventRepository outboxEventRepository;

    void record(Account account, String eventType) {
        record(account, eventType, Map.of());
    }

    void record(Account account, String eventType, Map<String, String> extraFields) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Account");
        event.setAggregateId(account.getId().toString());
        event.setEventType(eventType);
        event.setPayload(buildPayloadJson(account, extraFields));
        outboxEventRepository.save(event);
    }

    private String buildPayloadJson(Account account, Map<String, String> extraFields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"accountId\":\"").append(account.getId()).append("\",\"email\":\"")
                .append(OutboxJsonSupport.escapeJson(account.getEmail())).append('"');
        extraFields.forEach((key, value) -> sb.append(",\"").append(OutboxJsonSupport.escapeJson(key))
                .append("\":\"").append(OutboxJsonSupport.escapeJson(value)).append('"'));
        sb.append('}');
        return sb.toString();
    }
}
