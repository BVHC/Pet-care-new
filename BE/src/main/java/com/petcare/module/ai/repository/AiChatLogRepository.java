package com.petcare.module.ai.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiChatLog;

/** Bảng {@code ai_chat_logs} (docs/adr/0130): chỉ thêm; ST23 xóa theo lô quá hạn lưu (BR-AI-24). */
public interface AiChatLogRepository extends JpaRepository<AiChatLog, Long> {

    /** Xóa tối đa {@code limit} dòng có {@code created_at < cutoff}, cũ nhất trước; dùng index {@code created_at}. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            DELETE FROM ai_chat_logs WHERE id IN (
                SELECT id FROM ai_chat_logs WHERE created_at < :cutoff ORDER BY created_at, id LIMIT :limit)""",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
