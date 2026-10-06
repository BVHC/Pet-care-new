package com.petcare.module.identity.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.repository.SessionRepository;

/**
 * Xóa phiên đã hết hạn quá thời gian lưu (docs/adr/0008). Mỗi lời gọi là một transaction riêng: job gọi lặp theo lô,
 * lô lỗi không rollback các lô đã xóa (convention 07 §7.4). Tách khỏi {@link SessionService} vì các hàm ở đó là
 * {@code MANDATORY} trong use case của người dùng.
 */
@Service
public class SessionCleanupService {

    private final SessionRepository sessions;

    public SessionCleanupService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** Xóa tối đa {@code limit} phiên có {@code expires_at < cutoff}; trả số dòng đã xóa. */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        return sessions.deleteExpiredBefore(cutoff, limit);
    }
}
