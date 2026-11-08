package com.petcare.module.identity.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.repository.RefreshTokenRepository;

/**
 * Xóa refresh token đã hết hạn quá thời hạn giữ (docs/adr/0114, trả D054). Mỗi lời gọi là một transaction riêng: job
 * gọi lặp theo lô, lô lỗi không rollback các lô đã xóa (convention 07 §7.4). Tách khỏi {@link RefreshTokenService}
 * (luồng refresh của người dùng) và {@link SessionCleanupService} (xóa token cùng phiên): job này chỉ khóa
 * {@code refresh_tokens}, không xin {@code sessions} hay {@code accounts}.
 */
@Service
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokens;

    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * Xóa tối đa {@code limit} dòng {@code refresh_tokens} có {@code expires_at < cutoff}; trả số dòng đã xóa. Thứ tự:
     * khóa lô ({@code SKIP LOCKED}) → xóa đúng các id đó. Không kiểm {@code used_at}; không ghi audit (không thuộc
     * BR-QT-15).
     */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        List<Long> ids = refreshTokens.lockExpiredIdsBefore(cutoff, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        return refreshTokens.deleteByIds(ids);
    }
}
