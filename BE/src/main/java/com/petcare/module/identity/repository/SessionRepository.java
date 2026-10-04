package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.Session;

/**
 * Ba câu hủy phiên chạy trong transaction của use case gọi tới ({@code SessionService}, {@code MANDATORY}).
 * {@code flushAutomatically} đẩy thay đổi đang chờ của use case xuống DB trước UPDATE hàng loạt. <b>Không</b>
 * {@code clearAutomatically}: clear sẽ detach entity mà use case đã nạp (ví dụ {@code Account} khi đổi mật khẩu),
 * làm thay đổi sau lời gọi revoke mất âm thầm. Không cần clear vì việc đọc phiên ({@link #findAuthView}) luôn đọc DB.
 */
public interface SessionRepository extends JpaRepository<Session, Long> {

    /** Một query cho mỗi request đã có token hợp lệ chữ ký (docs/adr/0003). */
    @Query("""
            SELECT new com.petcare.module.identity.repository.SessionAuthView(
                s.id, s.accountId, s.tokenHash, s.expiresAt, s.revokedAt,
                a.email, a.role, a.status, a.locked, a.mustChangePassword, a.lastSeenAt, sp.branchId)
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
}
