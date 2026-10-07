package com.petcare.module.identity.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.OtpTokenRepository;

/**
 * ST02 — xóa tài khoản {@code PENDING} quá hạn cùng hồ sơ khách online tạo kèm (03 Tài khoản#3, BR-TK-08,
 * docs/adr/0013). Mỗi {@link #purge} là một transaction cho một tài khoản (convention 07 §7.4): lỗi ở một tài khoản
 * không rollback tài khoản khác, và lỗi ở bất kỳ bước nào rollback cả {@code otp_tokens} lẫn hồ sơ. Không ghi audit
 * (BR-QT-15), không gửi thông báo, không phát sự kiện. Tách khỏi {@link RegistrationService} như
 * {@link SessionCleanupService}: không phải use case của người dùng.
 */
@Service
public class PendingAccountCleanupService {

    /** Kết quả xử lý một tài khoản. */
    public enum PurgeResult {
        /** Đã xóa tài khoản, mã OTP và hồ sơ online. */
        DELETED,
        /** Không xóa: đã xác thực, đã bị xóa, hoặc đang bị verify/resend khóa — lượt sau xét lại. */
        SKIPPED
    }

    private final AccountRepository accounts;
    private final OtpTokenRepository otpTokens;
    private final CustomerApi customers;

    public PendingAccountCleanupService(AccountRepository accounts, OtpTokenRepository otpTokens,
            CustomerApi customers) {
        this.accounts = accounts;
        this.otpTokens = otpTokens;
        this.customers = customers;
    }

    /** Tối đa {@code limit} id tài khoản {@code PENDING} quá hạn tại {@code now}, có {@code id > afterId}. Không khóa. */
    @Transactional(readOnly = true)
    public List<Long> findExpiredIds(Instant now, long afterId, int limit) {
        return accounts.findExpiredPendingIds(now, afterId, limit);
    }

    /**
     * Tài khoản#3. Khóa dòng {@code accounts} trước (thứ tự khóa {@code accounts → otp_tokens}, docs/adr/0011) và kiểm
     * lại điều kiện dưới khóa; rồi xóa theo thứ tự FK {@code RESTRICT}: {@code otp_tokens} → hồ sơ online →
     * {@code accounts} (system-overview §4b). {@code CustomerApi} chỉ được gọi khi đã giữ khóa, nên hai lượt chạy song
     * song không xóa trùng.
     */
    @Transactional
    public PurgeResult purge(long accountId, Instant now) {
        if (accounts.lockExpiredPending(accountId, now).isEmpty()) {
            return PurgeResult.SKIPPED;
        }
        otpTokens.deleteByAccountId(accountId);
        customers.deleteOnlineProfileOfUnverifiedAccount(accountId);
        if (accounts.deletePendingById(accountId) != 1) {
            throw new IllegalStateException("Tài khoản PENDING #" + accountId + " đã khóa nhưng không xóa được");
        }
        return PurgeResult.DELETED;
    }
}
