package com.petcare.module.ai.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.repository.AiChatLogRepository;

/**
 * Xóa nhật ký hỏi đáp quá hạn lưu (ST23, BR-AI-24, docs/adr/0130). Mỗi lời gọi là một transaction riêng: job gọi lặp
 * theo lô, lô lỗi không rollback các lô đã xóa. Không ghi audit (nhật ký không thuộc BR-AI-17).
 */
@Service
public class ChatLogCleanupService {

    private final AiChatLogRepository logs;

    public ChatLogCleanupService(AiChatLogRepository logs) {
        this.logs = logs;
    }

    /** Xóa tối đa {@code limit} dòng có {@code created_at < cutoff}, cũ nhất trước; trả số dòng đã xóa. */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        return logs.deleteExpiredBefore(cutoff, limit);
    }
}
