package com.petcare.platform.security.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** RULE-01-06 — Logout tra refresh token của phiên hiện tại từ JTI access token. */
    Optional<RefreshTokenEntity> findByAccessTokenJtiAndRevokedAtIsNull(UUID accessTokenJti);

    /**
     * Tra theo access_token_jti KHÔNG lọc revoked_at — dùng khi access token đưa lên đã lỗi
     * thời (đã bị rotate) để lần theo chuỗi {@code replaced_by} tới generation đang sống của
     * cùng phiên (xem RefreshTokenService#revokeByAccessTokenJti).
     */
    Optional<RefreshTokenEntity> findByAccessTokenJti(UUID accessTokenJti);

    List<RefreshTokenEntity> findAllByAccountIdAndRevokedAtIsNull(UUID accountId);

    /**
     * Bucket "default" (ADR-0003, 7 ngày): hết hạn tự nhiên chưa revoke, HOẶC
     * revoke do ROTATED (rotation bình thường, không phải sự kiện bảo mật).
     * Xoá theo batch (DELETE ... WHERE id IN (SELECT ... LIMIT n)) để tránh
     * khoá bảng lớn khi refresh_tokens tăng dần theo thời gian.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM refresh_tokens "
            + "WHERE (revoked_at IS NULL AND expires_at < :cutoff) "
            + "OR (revoked_at IS NOT NULL AND revoke_reason = 'ROTATED' AND revoked_at < :cutoff) "
            + "LIMIT :batchSize)", nativeQuery = true)
    int deleteExpiredOrRotatedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Bucket "non-ROTATED" / extended-retention (ADR-0003, 30 ngày): mọi row đã
     * revoke với lý do KHÁC ROTATED (bao gồm revoke_reason NULL bất thường, và
     * bất kỳ reason mới nào chưa từng biết tới) — thiết kế catch-all, không phải
     * whitelist, để không bao giờ "quên xoá" một reason mới phát sinh sau này.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE id IN (SELECT id FROM refresh_tokens "
            + "WHERE revoked_at IS NOT NULL AND (revoke_reason IS NULL OR revoke_reason <> 'ROTATED') "
            + "AND revoked_at < :cutoff LIMIT :batchSize)", nativeQuery = true)
    int deleteNonRotatedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Quan sát/vận hành: phát hiện revoke_reason lạ (khác ROTATED, không nằm
     * trong danh sách reason bảo mật đã biết) để cảnh báo — không ảnh hưởng
     * logic xoá ở trên (đã an toàn với catch-all).
     */
    @Query(value = "SELECT DISTINCT revoke_reason FROM refresh_tokens WHERE revoked_at IS NOT NULL "
            + "AND revoke_reason IS NOT NULL AND revoke_reason <> 'ROTATED' "
            + "AND revoke_reason NOT IN (:knownSecurityReasons) LIMIT 20", nativeQuery = true)
    List<String> findUnrecognizedRevokeReasons(@Param("knownSecurityReasons") List<String> knownSecurityReasons);
}
