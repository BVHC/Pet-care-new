package com.petcare.module.identity.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.repository.RefreshTokenRepository;
import com.petcare.module.identity.repository.SessionRepository;

/**
 * Xóa phiên đã hết hạn quá thời gian lưu (docs/adr/0008). Mỗi lời gọi là một transaction riêng: job gọi lặp theo lô,
 * lô lỗi không rollback các lô đã xóa (convention 07 §7.4). Tách khỏi {@link SessionService} vì các hàm ở đó là
 * {@code MANDATORY} trong use case của người dùng.
 */
@Service
public class SessionCleanupService {

    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;

    public SessionCleanupService(SessionRepository sessions, RefreshTokenRepository refreshTokens) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Xóa tối đa {@code limit} phiên có {@code expires_at < cutoff}; trả số phiên đã xóa. Thứ tự: khóa phiên
     * ({@code SKIP LOCKED}) → xóa refresh token của chúng → xóa phiên, vì {@code refresh_tokens.session_id} là
     * {@code RESTRICT} (docs/adr/0091). Refresh không chạm {@code sessions} khi phiên đã hết hạn (nó từ chối ở bước
     * kiểm hạn, trước mọi lệnh ghi), nên không có vòng chờ khóa với cleanup.
     */
    @Transactional
    public int deleteExpiredBatch(Instant cutoff, int limit) {
        List<Long> ids = sessions.lockExpiredIdsBefore(cutoff, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        refreshTokens.deleteBySessionIds(ids);
        return sessions.deleteByIds(ids);
    }
}
