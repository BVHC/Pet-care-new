package com.petcare.module.care.repository;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.care.entity.Notification;

/**
 * Bảng {@code notifications}. Worker ST20 IN_APP ghi ({@code save}, docs/adr/0014); UC88 đọc và đánh dấu đã đọc
 * (care-v1 #5–8, docs/adr/0039). Mọi câu của UC88 lọc theo {@code account_id} của người đang đăng nhập và viết điều
 * kiện thành hằng số (không {@code :x IS NULL OR}) để dùng được index. Hai index: V1
 * {@code ix_notifications_account_read_created (account_id, read_at, created_at DESC)} cho các câu đếm và UPDATE chưa
 * đọc; V32 {@code ix_notifications_account_created (account_id, created_at DESC, id DESC)} cho thứ tự của danh sách
 * (docs/adr/0099, trả D022). Hai câu UPDATE hàng loạt bỏ qua auditing listener nên tự đặt {@code updated_at}
 * (docs/adr/0015).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * care-v1 #5, mọi thông báo của tài khoản, mới nhất trước. Khớp thứ tự của {@code ix_notifications_account_created}
     * nên không Sort. {@link #findMineUnread} do planner chọn giữa hai index (ít dòng chưa đọc thì index V1 cộng Sort nhỏ
     * rẻ hơn).
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE account_id = :accountId
            ORDER BY created_at DESC, id DESC
            """,
            countQuery = "SELECT count(*) FROM notifications WHERE account_id = :accountId",
            nativeQuery = true)
    Page<Notification> findMine(@Param("accountId") Long accountId, Pageable pageable);

    /** care-v1 #5 với {@code unreadOnly=true}. */
    @Query(value = """
            SELECT * FROM notifications
            WHERE account_id = :accountId AND read_at IS NULL
            ORDER BY created_at DESC, id DESC
            """,
            countQuery = "SELECT count(*) FROM notifications WHERE account_id = :accountId AND read_at IS NULL",
            nativeQuery = true)
    Page<Notification> findMineUnread(@Param("accountId") Long accountId, Pageable pageable);

    /** care-v1 #6. */
    @Query(value = "SELECT count(*) FROM notifications WHERE account_id = :accountId AND read_at IS NULL",
            nativeQuery = true)
    long countUnread(@Param("accountId") Long accountId);

    /**
     * care-v1 #7. Chỉ đổi dòng của chính chủ và chưa đọc: gọi lại giữ {@code read_at} lần đầu. Trả 0 khi dòng không
     * tồn tại, của người khác hoặc đã đọc — service phân biệt bằng {@link #existsByIdAndAccountId}.
     */
    @Modifying
    @Query(value = """
            UPDATE notifications SET read_at = :now, updated_at = :now
            WHERE id = :id AND account_id = :accountId AND read_at IS NULL
            """, nativeQuery = true)
    int markRead(@Param("id") Long id, @Param("accountId") Long accountId, @Param("now") Instant now);

    boolean existsByIdAndAccountId(Long id, Long accountId);

    /** care-v1 #8. Mọi dòng chưa đọc thấy được lúc câu lệnh chạy (docs/adr/0039); dòng đã đọc giữ {@code read_at}. */
    @Modifying
    @Query(value = """
            UPDATE notifications SET read_at = :now, updated_at = :now
            WHERE account_id = :accountId AND read_at IS NULL
            """, nativeQuery = true)
    int markAllRead(@Param("accountId") Long accountId, @Param("now") Instant now);

    /**
     * Một lô của job dọn thông báo (docs/adr/0112, trả D023), lượt <b>đã đọc</b>: xóa tối đa {@code limit} dòng có
     * {@code read_at IS NOT NULL AND created_at < cutoff}. {@code SKIP LOCKED}: không chờ dòng mà {@link #markRead} /
     * {@link #markAllRead} đang khóa (job không xin {@code accounts}) nên không có chu trình deadlock; dòng bị bỏ qua
     * được xóa ở lượt sau. Điều kiện {@code read_at} được đọc lại dưới khóa. Không bảng nào trỏ tới {@code notifications}.
     */
    @Modifying
    @Query(value = """
            DELETE FROM notifications WHERE id IN (
                SELECT id FROM notifications
                WHERE read_at IS NOT NULL AND created_at < :cutoff
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteReadBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Như {@link #deleteReadBefore}, lượt <b>chưa đọc</b> ({@code read_at IS NULL}) với mốc cắt dài hơn. */
    @Modifying
    @Query(value = """
            DELETE FROM notifications WHERE id IN (
                SELECT id FROM notifications
                WHERE read_at IS NULL AND created_at < :cutoff
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteUnreadBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
