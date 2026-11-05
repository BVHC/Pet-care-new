package com.petcare.module.care.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.repository.NotificationRepository;

/**
 * Xóa thông báo trong app quá thời hạn giữ (docs/adr/0112, trả D023). Mỗi lời gọi là một transaction riêng: job gọi
 * lặp theo lô, lô lỗi không rollback các lô đã xóa (convention 07 §7.4). Tách khỏi {@link MyNotificationService} vì
 * đó là use case của người dùng (lọc theo tài khoản của token); job này không có người dùng và không khóa
 * {@code accounts}. Không ghi audit (không thuộc BR-QT-15).
 */
@Service
public class NotificationCleanupService {

    private final NotificationRepository notifications;

    public NotificationCleanupService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** Xóa tối đa {@code limit} thông báo <b>đã đọc</b> có {@code created_at < cutoff}; trả số dòng đã xóa. */
    @Transactional
    public int deleteReadBatch(Instant cutoff, int limit) {
        return notifications.deleteReadBefore(cutoff, limit);
    }

    /** Xóa tối đa {@code limit} thông báo <b>chưa đọc</b> có {@code created_at < cutoff}; trả số dòng đã xóa. */
    @Transactional
    public int deleteUnreadBatch(Instant cutoff, int limit) {
        return notifications.deleteUnreadBefore(cutoff, limit);
    }
}
