package com.petcare.module.care.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.entity.NotificationOutbox;
import com.petcare.module.care.entity.OutboxStatus;

/**
 * Worker ST20 lấy từng dòng đến hạn theo luồng. Luồng IN_APP xem các câu {@code …InApp} bên dưới (docs/adr/0014).
 * Email (docs/adr/0012): HIGH = {@code template_code IN (…)}, NORMAL =
 * {@code NOT IN (…)} cùng một tập mã, nên hai luồng không bao giờ chọn cùng dòng. {@code SKIP LOCKED}: không chờ dòng
 * lượt khác đang gửi. Native vì JPQL không có {@code LIMIT}/{@code SKIP LOCKED}. Index
 * {@code (status, template_code, next_attempt_at)} (V4) và {@code (status, next_attempt_at)} (V1).
 */
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'EMAIL' AND template_code IN (:codes) AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<NotificationOutbox> lockNextDueEmailIn(@Param("now") Instant now,
            @Param("codes") Collection<String> codes);

    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'EMAIL' AND template_code NOT IN (:codes) AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<NotificationOutbox> lockNextDueEmailNotIn(@Param("now") Instant now,
            @Param("codes") Collection<String> codes);

    /** Luồng NORMAL theo lô (docs/adr/0017): khóa tối đa {@code limit} dòng đến hạn, cùng thứ tự như câu một dòng. */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'EMAIL' AND template_code NOT IN (:codes) AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationOutbox> lockNextDueEmailBatchNotIn(@Param("now") Instant now,
            @Param("codes") Collection<String> codes, @Param("limit") int limit);

    @Query("""
            SELECT new com.petcare.module.care.repository.LaneBacklog(count(o), min(o.nextAttemptAt))
            FROM NotificationOutbox o
            WHERE o.status = :status AND o.channel = :channel AND o.templateCode IN :codes AND o.nextAttemptAt <= :now
            """)
    LaneBacklog backlogIn(@Param("now") Instant now, @Param("codes") Collection<String> codes,
            @Param("status") OutboxStatus status, @Param("channel") Channel channel);

    @Query("""
            SELECT new com.petcare.module.care.repository.LaneBacklog(count(o), min(o.nextAttemptAt))
            FROM NotificationOutbox o
            WHERE o.status = :status AND o.channel = :channel AND o.templateCode NOT IN :codes
              AND o.nextAttemptAt <= :now
            """)
    LaneBacklog backlogNotIn(@Param("now") Instant now, @Param("codes") Collection<String> codes,
            @Param("status") OutboxStatus status, @Param("channel") Channel channel);

    /*
     * Luồng IN_APP (docs/adr/0014): index partial ix_notification_outbox_in_app_due (V6). Điều kiện status / channel
     * viết hằng số, không dùng tham số bind: với generic plan (driver chuyển sang prepared statement phía server sau
     * vài lần gọi) planner không chứng minh được predicate của index partial và quay về quét dải PENDING của cả email.
     */

    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'IN_APP' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<NotificationOutbox> lockNextDueInApp(@Param("now") Instant now);

    @Query(value = """
            SELECT count(*) FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'IN_APP' AND next_attempt_at <= :now
            """, nativeQuery = true)
    long countDueInApp(@Param("now") Instant now);

    /** Dòng IN_APP đến hạn lâu nhất, không khóa — chỉ để đo trễ. */
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND channel = 'IN_APP' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT 1
            """, nativeQuery = true)
    Optional<NotificationOutbox> findOldestDueInApp(@Param("now") Instant now);

    /**
     * Giới hạn thời gian "idle in transaction" của transaction hiện tại ({@code set_config(…, true)} = {@code SET LOCAL}).
     * Gửi SMTP chạy giữa transaction: nếu timeout SMTP không cắt được, Postgres tự hủy phiên thay vì giữ khóa và
     * connection vô hạn (docs/adr/0012).
     */
    @Query(value = "SELECT set_config('idle_in_transaction_session_timeout', :timeout, true)", nativeQuery = true)
    String limitIdleInTransaction(@Param("timeout") String timeout);

    /**
     * Dọn dòng đã xử lý (docs/adr/0016, nợ D007): xóa tối đa {@code limit} dòng {@code status} ({@code SENT} hoặc
     * {@code FAILED}) có {@code created_at < cutoff}; trả số dòng đã xóa. Theo {@code id} tăng dần (dòng cũ nhất có id
     * nhỏ nhất); {@code SKIP LOCKED} để không chờ dòng đang bị khóa. Không chạm dòng {@code PENDING} mà worker xử lý.
     */
    @Modifying
    @Query(value = """
            DELETE FROM notification_outbox WHERE id IN (
                SELECT id FROM notification_outbox
                WHERE status = :status AND created_at < :cutoff
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("status") String status, @Param("cutoff") Instant cutoff,
            @Param("limit") int limit);
}
