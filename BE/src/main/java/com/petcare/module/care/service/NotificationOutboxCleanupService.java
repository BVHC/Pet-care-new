package com.petcare.module.care.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.repository.NotificationOutboxRepository;

/**
 * Xóa dòng {@code notification_outbox} đã xử lý quá thời hạn giữ (docs/adr/0016). Mỗi lời gọi là một transaction
 * riêng: job gọi lặp theo lô, lô lỗi không rollback các lô đã xóa (convention 07 §7.4).
 */
@Service
public class NotificationOutboxCleanupService {

    private final NotificationOutboxRepository outbox;

    public NotificationOutboxCleanupService(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    /** Xóa tối đa {@code limit} dòng {@code status} có {@code created_at < cutoff}; trả số dòng đã xóa. */
    @Transactional
    public int deleteProcessedBatch(OutboxStatus status, Instant cutoff, int limit) {
        if (status == OutboxStatus.PENDING) {
            throw new IllegalArgumentException("Không dọn dòng PENDING: worker ST20 còn xử lý");
        }
        return outbox.deleteProcessedBefore(status.name(), cutoff, limit);
    }
}
