package com.petcare.module.identity.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.repository.OtpTokenRepository;

/**
 * Xóa mã OTP đã hết hạn quá thời hạn giữ (docs/adr/0111, trả D012). Mỗi lời gọi là một transaction riêng: job gọi lặp
 * theo lô, lô lỗi không rollback các lô đã xóa (convention 07 §7.4). Tách khỏi {@link OtpService} vì các hàm ở đó là
 * {@code MANDATORY} trong use case của người dùng và {@code OtpService} luôn chạy dưới khóa {@code accounts}; job này
 * không khóa {@code accounts}.
 */
@Service
public class OtpTokenCleanupService {

    private final OtpTokenRepository otps;

    public OtpTokenCleanupService(OtpTokenRepository otps) {
        this.otps = otps;
    }

    /**
     * Xóa tối đa {@code limit} dòng {@code otp_tokens} có {@code expires_at < cutoff}; trả số dòng đã xóa. Không kiểm
     * {@code consumed_at} / {@code invalidated_at}; không ghi audit (không thuộc BR-QT-15).
     */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        return otps.deleteExpiredBefore(cutoff, limit);
    }
}
