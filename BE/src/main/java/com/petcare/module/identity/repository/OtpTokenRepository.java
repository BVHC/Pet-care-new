package com.petcare.module.identity.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.entity.OtpToken;

/**
 * Hai câu đếm của BR-TK-07 dùng index {@code ix_otp_tokens_target_email_created_at}; tính mọi mục đích, vì quota là
 * theo email nhận. UPDATE hàng loạt dùng {@code flushAutomatically} và không clear persistence context, như
 * {@link SessionRepository}.
 */
public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {

    /** Mã gửi gần nhất tới email — khoảng cách tối thiểu giữa hai lần gửi (BR-TK-07). */
    Optional<OtpToken> findTopByTargetEmailOrderByCreatedAtDesc(String targetEmail);

    /** Số mã đã gửi tới email trong cửa sổ (BR-TK-07). */
    long countByTargetEmailAndCreatedAtAfter(String targetEmail, Instant after);

    /** Mã cũ nhất còn trong cửa sổ — để báo khi nào gửi lại được (BR-TK-07). */
    Optional<OtpToken> findTopByTargetEmailAndCreatedAtAfterOrderByCreatedAtAsc(String targetEmail, Instant after);

    /**
     * Mã còn hiệu lực (chưa dùng, chưa hủy) mới nhất của tài khoản cho mục đích và email này — để xác thực
     * (BR-TK-05). Sắp theo {@code id}: tăng đúng thứ tự INSERT kể cả khi hai
     * mã cùng {@code created_at} (clock test đứng yên). Hết hạn hay chưa: service kiểm.
     */
    Optional<OtpToken> findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
            Long accountId, OtpPurpose purpose, String targetEmail);

    /**
     * Như method trên, thêm hồ sơ cần liên kết ({@code LINK_PROFILE}, BR-TK-19): mã gửi cho hồ sơ A không dùng được để
     * liên kết hồ sơ B dù hai hồ sơ cùng email ({@code customers.email} không duy nhất) — docs/adr/0025.
     */
    Optional<OtpToken> findTopByAccountIdAndPurposeAndTargetEmailAndCustomerIdAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
            Long accountId, OtpPurpose purpose, String targetEmail, Long customerId);

    /** BR-TK-05: sinh mã mới thì mã cũ cùng mục đích, cùng email, chưa dùng mất hiệu lực ngay. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OtpToken o SET o.invalidatedAt = :now
            WHERE o.targetEmail = :email AND o.purpose = :purpose
              AND o.consumedAt IS NULL AND o.invalidatedAt IS NULL
            """)
    int invalidateActive(@Param("email") String email, @Param("purpose") OtpPurpose purpose,
            @Param("now") Instant now);

    /**
     * BR-TK-05 cho {@code LINK_PROFILE} (docs/adr/0025): mã mới chỉ hủy mã còn hiệu lực của <b>chính tài khoản đó</b>
     * (mọi hồ sơ), không hủy mã tài khoản khác xin cho cùng email hồ sơ. Nhờ vậy mọi lệnh ghi lên mã của một tài khoản
     * đều nằm dưới khóa dòng {@code accounts} của tài khoản đó (docs/adr/0011). Dùng index {@code ix_otp_tokens_account_id}.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OtpToken o SET o.invalidatedAt = :now
            WHERE o.accountId = :accountId AND o.purpose = :purpose
              AND o.consumedAt IS NULL AND o.invalidatedAt IS NULL
            """)
    int invalidateActiveForAccount(@Param("accountId") Long accountId, @Param("purpose") OtpPurpose purpose,
            @Param("now") Instant now);

    /**
     * ST02 (docs/adr/0013): xóa mọi mã của tài khoản {@code PENDING} trước khi xóa tài khoản (FK {@code RESTRICT},
     * system-overview §4b). Dùng index {@code ix_otp_tokens_account_id} (V5).
     */
    @Modifying
    @Query("DELETE FROM OtpToken o WHERE o.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);
}
