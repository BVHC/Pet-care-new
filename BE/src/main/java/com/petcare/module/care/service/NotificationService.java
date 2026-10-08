package com.petcare.module.care.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.repository.NotificationOutboxRepository;

/**
 * Cài {@link NotificationApi}: chỉ ghi {@code notification_outbox} trong transaction của caller ({@code MANDATORY}),
 * worker ST20 gửi sau (06 §1, convention 07 §7.2). Caller chịu trách nhiệm payload đủ biến bắt buộc của mẫu
 * (BR-QT-14); mẫu không tồn tại thì FK {@code template_code} chặn. Request thiếu dữ liệu là lỗi lập trình →
 * {@link IllegalArgumentException}.
 */
@Service
public class NotificationService implements NotificationApi {

    /** Khóa trong payload chứa {@code linkUrl} của request; outbox không có cột riêng, ST20 dùng khi dựng nội dung. */
    static final String LINK_URL_KEY = "link_url";

    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    public NotificationService(NotificationOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("NotificationRequest is required");
        }
        if (request.templateCode() == null || request.templateCode().isBlank()) {
            throw new IllegalArgumentException("templateCode is required");
        }
        if (request.channel() == null) {
            throw new IllegalArgumentException("channel is required");
        }
        boolean hasEmail = request.recipientEmail() != null && !request.recipientEmail().isBlank();
        // Người nhận theo kênh (docs/adr/0012): email là snapshot lúc sự kiện, worker không tra lại theo tài khoản.
        if (request.channel() == Channel.EMAIL && !hasEmail) {
            throw new IllegalArgumentException("recipientEmail is required for EMAIL");
        }
        if (request.channel() == Channel.IN_APP && request.recipientAccountId() == null) {
            throw new IllegalArgumentException("recipientAccountId is required for IN_APP");
        }
        if (request.payload() == null) {
            throw new IllegalArgumentException("payload is required");
        }

        Map<String, Object> payload = new HashMap<>(request.payload());
        if (request.linkUrl() != null) {
            payload.put(LINK_URL_KEY, request.linkUrl());
        }
        outbox.save(NotificationOutbox.pending(request.channel(), request.templateCode(),
                hasEmail ? request.recipientEmail() : null, request.recipientAccountId(), payload,
                Instant.now(clock)));
    }
}
