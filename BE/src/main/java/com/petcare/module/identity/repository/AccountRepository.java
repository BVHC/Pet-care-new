package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.Account;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** BR-TK-01: {@code email} đã chuẩn hóa chữ thường; tính mọi trạng thái, kể cả {@code PENDING}. */
    boolean existsByEmail(String email);

    /**
     * {@code email} đã chuẩn hóa chữ thường (BR-TK-01). {@code SELECT … FOR UPDATE}: mọi use case OTP gọi đầu tiên,
     * trước khi đọc/ghi {@code otp_tokens}, để các luồng cùng tài khoản chạy tuần tự (docs/adr/0011).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.email = :email")
    Optional<Account> findByEmailForUpdate(@Param("email") String email);

    /**
     * BR-TN-06: ghi {@code last_seen_at} nếu giá trị cũ trước {@code threshold}. {@code SKIP LOCKED}: dòng đang bị
     * transaction khác khóa (khóa tài khoản, đổi mật khẩu…) thì bỏ qua lần này thay vì chờ, nên request không bao
     * giờ bị chặn vì cập nhật trạng thái online. Không đổi {@code updated_at} vì đây không phải sửa dữ liệu nghiệp vụ.
     */
    @Modifying
    @Query(value = """
            UPDATE accounts SET last_seen_at = :now
            WHERE id = (SELECT id FROM accounts
                        WHERE id = :accountId AND (last_seen_at IS NULL OR last_seen_at < :threshold)
                        FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int touchLastSeen(@Param("accountId") Long accountId, @Param("now") Instant now,
            @Param("threshold") Instant threshold);

    /**
     * ST02 (docs/adr/0013): tối đa {@code limit} id tài khoản {@code PENDING} đã quá hạn ({@code now >=
     * pending_expires_at}, BR-TK-08) có {@code id > afterId}, theo {@code id} — keyset để một lượt không đọc lại id cũ.
     * Không khóa: {@link #lockExpiredPending} kiểm lại dưới khóa. Dùng index partial {@code ix_accounts_pending_expires_at}.
     */
    @Query(value = """
            SELECT id FROM accounts
            WHERE status = 'PENDING' AND pending_expires_at <= :now AND id > :afterId
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, @Param("afterId") long afterId,
            @Param("limit") int limit);

    /**
     * ST02: khóa dòng nếu tài khoản <b>vẫn</b> {@code PENDING} và quá hạn — verify có thể đã commit sau
     * {@link #findExpiredPendingIds}. {@code SKIP LOCKED}: dòng đang bị verify/resend khóa thì bỏ qua, lượt sau xử lý
     * (docs/adr/0011 mục 4). Rỗng = không xóa.
     */
    @Query(value = """
            SELECT id FROM accounts
            WHERE id = :id AND status = 'PENDING' AND pending_expires_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockExpiredPending(@Param("id") long id, @Param("now") Instant now);

    /**
     * Tài khoản#3 — xóa cứng tài khoản {@code PENDING} (BR-TK-08). Gọi sau {@link #lockExpiredPending} và sau khi đã
     * xóa dòng con ({@code otp_tokens}, hồ sơ online).
     */
    @Modifying
    @Query(value = "DELETE FROM accounts WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int deletePendingById(@Param("id") long id);
}
