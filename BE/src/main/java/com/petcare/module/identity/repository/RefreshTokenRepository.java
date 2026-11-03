package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.entity.RefreshToken;

import jakarta.persistence.LockModeType;

/**
 * Refresh token xoay vòng (docs/adr/0091). Thứ tự khóa của refresh: {@code refresh_tokens} (dòng của token gửi lên)
 * → {@code sessions}; không bao giờ khóa {@code accounts}, nên không vòng với thứ tự {@code accounts → sessions}
 * (docs/adr/0021).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Khóa dòng token theo băm ({@code FOR NO KEY UPDATE}, Hibernate 6) rồi mới đọc: hai refresh cùng token xếp hàng
     * ở đây và lượt sau thấy {@code used_at} đã đặt. Phải là lần đầu dòng được nạp trong persistence context.
     * {@code MANDATORY}: ngoài transaction thì khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Đánh dấu đã dùng; phải trúng 1 dòng (caller đang giữ khóa dòng và đã kiểm {@code used_at IS NULL}). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.usedAt = :now, t.updatedAt = :now
            WHERE t.id = :id AND t.usedAt IS NULL
            """)
    int markUsed(@Param("id") Long id, @Param("now") Instant now);

    /** Xóa token của các phiên sắp bị job dọn phiên xóa (FK {@code RESTRICT}, docs/adr/0008, docs/adr/0091). */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.sessionId IN :sessionIds")
    int deleteBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
