package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.api.Role;
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
     * Đăng nhập, bước đọc không khóa (docs/adr/0019 mục 4): {@code (id, password_hash)} theo email đã chuẩn hóa chữ
     * thường. Projection, không nạp entity — xem {@link AccountCredential}.
     */
    @Query("""
            SELECT new com.petcare.module.identity.repository.AccountCredential(a.id, a.passwordHash)
            FROM Account a WHERE a.email = :email
            """)
    Optional<AccountCredential> findCredentialByEmail(@Param("email") String email);

    /**
     * Đổi mật khẩu, bước đọc không khóa (docs/adr/0022): {@code password_hash} và {@code locked_until} của tài khoản
     * đang đăng nhập. Projection, không nạp entity — cùng lý do {@link AccountCredential}.
     */
    @Query("""
            SELECT new com.petcare.module.identity.repository.AccountPasswordState(a.passwordHash, a.lockedUntil)
            FROM Account a WHERE a.id = :id
            """)
    Optional<AccountPasswordState> findPasswordStateById(@Param("id") Long id);

    /**
     * Đặt lại mật khẩu, bước đọc không khóa (docs/adr/0023): trạng thái và hash theo email đã chuẩn hóa chữ thường.
     * Projection, không nạp entity — xem {@link AccountResetState}. Dùng {@code uq_accounts_email}.
     */
    @Query("""
            SELECT new com.petcare.module.identity.repository.AccountResetState(a.id, a.status, a.locked, a.passwordHash)
            FROM Account a WHERE a.email = :email
            """)
    Optional<AccountResetState> findResetStateByEmail(@Param("email") String email);

    /**
     * Đăng nhập, đổi mật khẩu, đặt lại mật khẩu — sau khi so BCrypt: {@code SELECT … FOR UPDATE} theo {@code id} để
     * đọc lại trạng thái và ghi bộ đếm dưới khóa (docs/adr/0019 mục 4, docs/adr/0022, docs/adr/0023). Rỗng = tài khoản vừa bị xóa (ST02) giữa bước đọc và bước khóa.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * BR-TN-06: ghi {@code last_seen_at} nếu giá trị cũ trước {@code threshold} <b>và</b> phiên {@code sessionId} chưa
     * bị hủy. {@code SKIP LOCKED}: dòng đang bị transaction khác khóa (đăng xuất, khóa tài khoản, đổi mật khẩu…) thì
     * bỏ qua lần này thay vì chờ, nên request không bao giờ bị chặn vì cập nhật trạng thái online. Không đổi
     * {@code updated_at} vì đây không phải sửa dữ liệu nghiệp vụ.
     * <p>
     * Điều kiện phiên đóng race "request cùng token đang chạy ghi lại {@code last_seen_at} sau khi đăng xuất"
     * (docs/adr/0021 mục 3): đăng xuất commit trước câu này → {@code EXISTS} sai; đăng xuất đang chạy → nó giữ khóa dòng
     * {@code accounts} tới lúc commit ({@link #clearLastSeen} chạy trước khi hủy phiên) → câu này bỏ qua dòng.
     */
    @Modifying
    @Query(value = """
            UPDATE accounts SET last_seen_at = :now
            WHERE id = (SELECT a.id FROM accounts a
                        WHERE a.id = :accountId AND (a.last_seen_at IS NULL OR a.last_seen_at < :threshold)
                          AND EXISTS (SELECT 1 FROM sessions s WHERE s.id = :sessionId AND s.revoked_at IS NULL)
                        FOR UPDATE OF a SKIP LOCKED)
            """, nativeQuery = true)
    int touchLastSeen(@Param("accountId") Long accountId, @Param("sessionId") Long sessionId,
            @Param("now") Instant now, @Param("threshold") Instant threshold);

    /**
     * Đăng xuất (BR-TN-06 "đăng xuất thì chuyển offline ngay", docs/adr/0021): đặt {@code last_seen_at = NULL}. Gọi
     * <b>trước</b> khi hủy phiên, để transaction khóa {@code accounts → sessions} như mọi luồng hủy phiên khác (không
     * deadlock) và giữ khóa dòng tới lúc commit (đóng race với {@link #touchLastSeen}). Cố ý <b>không</b>
     * {@code SKIP LOCKED}: phải chờ transaction đang khóa dòng (đăng nhập…) rồi mới xóa. Dòng đã {@code NULL} (khách,
     * nhân viên chưa từng có request) không bị khóa. Không đổi {@code updated_at}, như {@link #touchLastSeen}.
     */
    @Modifying
    @Query(value = "UPDATE accounts SET last_seen_at = NULL WHERE id = :accountId AND last_seen_at IS NOT NULL",
            nativeQuery = true)
    int clearLastSeen(@Param("accountId") Long accountId);

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

    /**
     * {@code StaffDirectoryApi.findActiveSuperManagerIds} (docs/adr/0024): tài khoản theo role đang hoạt động
     * ({@code ACTIVE}, không bị khóa), không cần {@code staff_profiles}. Projection, không nạp entity.
     */
    @Query("""
            SELECT a.id FROM Account a
            WHERE a.role = :role
              AND a.status = com.petcare.module.identity.entity.AccountStatus.ACTIVE AND a.locked = false
            ORDER BY a.id
            """)
    List<Long> findActiveIdsByRole(@Param("role") Role role);

    /** {@code StaffDirectoryApi.findEmail}: mọi trạng thái, mọi role. Projection, không nạp entity. */
    @Query("SELECT a.email FROM Account a WHERE a.id = :id")
    Optional<String> findEmailById(@Param("id") Long id);
}
