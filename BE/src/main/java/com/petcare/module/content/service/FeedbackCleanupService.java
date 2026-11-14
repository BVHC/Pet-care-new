package com.petcare.module.content.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.repository.FeedbackRepository;

/**
 * Xóa feedback quá hạn lưu (ST24, BR-DG-05, docs/adr/0122, 0131). Mỗi lời gọi là một transaction riêng: job gọi lặp theo
 * lô, lô lỗi không rollback các lô đã xóa. Không ghi audit (không thuộc BR-QT-15) và không khóa {@code customers}: job
 * chỉ chạm bảng {@code feedbacks}.
 */
@Service
public class FeedbackCleanupService {

    private final FeedbackRepository feedbacks;

    public FeedbackCleanupService(FeedbackRepository feedbacks) {
        this.feedbacks = feedbacks;
    }

    /** Xóa tối đa {@code limit} feedback có {@code created_at < cutoff}, cũ nhất trước; trả số dòng đã xóa. */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        return feedbacks.deleteExpiredBefore(cutoff, limit);
    }
}
