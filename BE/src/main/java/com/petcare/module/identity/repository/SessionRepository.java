package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.entity.Session;

/**
 * Ba câu hủy phiên chạy trong transaction của use case gọi tới ({@code SessionService}, {@code MANDATORY}).
 * {@code flushAutomatically} đẩy thay đổi đang chờ của use case xuống DB trước UPDATE hàng loạt. <b>Không</b>
 * {@code clearAutomatically}: clear sẽ detach entity mà use case đã nạp (ví dụ {@code Account} khi đổi mật khẩu),
 * làm thay đổi sau lời gọi revoke mất âm thầm. Không cần clear vì việc đọc phiên ({@link #findAuthView}) luôn đọc DB.
 * Phiên hết hạn quá thời gian lưu bị job xóa bằng {@link #lockExpiredIdsBefore} + {@link #deleteByIds} (docs/adr/0008).
 */
public interface SessionRepository extends JpaRepository<Session, Long> {

    /** Một query cho mỗi request đã có token hợp lệ chữ ký (docs/adr/0003). */
    @Query("""
            SELECT new com.petcare.module.identity.repository.SessionAuthView(
                s.id, s.accountId, s.tokenHash, s.expiresAt, s.revokedAt,
                a.email, a.role, a.status, a.locked, a.mustChangePassword, sp.branchId)
            FROM Session s
            JOIN Account a ON a.id = s.accountId
            LEFT JOIN StaffProfile sp ON sp.accountId = a.id
            WHERE s.id = :sessionId
            """)
    Optional<SessionAuthView> findAuthView(@Param("sessionId") Long sessionId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Session s SET s.revokedAt = :now, s.updatedAt = :now
            WHERE s.id = :sessionId AND s.revokedAt IS NULL
            """)
    int revokeById(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Session s SET s.revokedAt = :now, s.updatedAt = :now
            WHERE s.accountId = :accountId AND s.revokedAt IS NULL
            """)
    int revokeAllByAccount(@Param("accountId") Long accountId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Session s SET s.revokedAt = :now, s.updatedAt = :now
            WHERE s.accountId = :accountId AND s.id <> :keepSessionId AND s.revokedAt IS NULL
            """)
    int revokeAllByAccountExcept(@Param("accountId") Long accountId, @Param("keepSessionId") Long keepSessionId,
            @Param("now") Instant now);

    /**
     * Refresh (docs/adr/0091): đổi {@code token_hash} (jti mới) và hạn access token của phiên còn hiệu lực, một câu có
     * điều kiện nên phải trúng đúng 1 dòng; 0 dòng = phiên vừa bị hủy hoặc hết hạn giữa chừng. Native vì
     * {@code token_hash} khai {@code updatable = false} ở entity. Refresh chỉ khóa {@code refresh_tokens} rồi dòng
     * {@code sessions} này, không khóa {@code accounts}.
     */
    @Modifying
    @Query(value = """
            UPDATE sessions SET token_hash = :tokenHash, access_expires_at = :accessExpiresAt, updated_at = :now
            WHERE id = :sessionId AND revoked_at IS NULL AND expires_at > :now
            """, nativeQuery = true)
    int rotateAccess(@Param("sessionId") Long sessionId, @Param("tokenHash") String tokenHash,
            @Param("accessExpiresAt") Instant accessExpiresAt, @Param("now") Instant now);

    /**
     * Một lô của job dọn phiên (docs/adr/0008): khóa tối đa {@code limit} phiên có {@code expires_at < cutoff}.
     * Điều kiện chỉ theo {@code expires_at}: phiên đã hủy cũng có {@code expires_at ≤ created_at + session.ttl_hours}.
     * {@code SKIP LOCKED}: không chờ dòng mà use case khác đang khóa (các câu revoke ở trên cũng chạm dòng đã hết hạn
     * chưa hủy), nên không thể có chu trình deadlock; dòng bị bỏ qua được xóa ở lượt sau. Caller xóa
     * {@code refresh_tokens} của các id này trước rồi mới {@link #deleteByIds} (FK {@code RESTRICT}, docs/adr/0091).
     * {@code MANDATORY}: ngoài transaction thì khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id FROM sessions WHERE expires_at < :cutoff
            ORDER BY id LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockExpiredIdsBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM Session s WHERE s.id IN :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
