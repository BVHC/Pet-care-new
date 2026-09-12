package com.petcare.platform.security.token;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Xoá cứng (hard delete) các row refresh_tokens đã hết giá trị audit theo
 * chính sách retention 2 mức (ADR-0003): 7 ngày cho hết hạn tự nhiên/ROTATED,
 * 30 ngày (extended-retention) cho mọi trường hợp revoke khác. Mỗi method xử
 * lý đúng 1 batch, được RefreshTokenCleanupJob gọi lặp lại — @Transactional
 * theo batch (không bao trọn vòng lặp) để nhả lock sớm giữa các batch.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository repository;

    @Transactional
    public int cleanupExpiredOrRotatedBatch(Instant cutoff, int batchSize) {
        int deleted = repository.deleteExpiredOrRotatedBatch(cutoff, batchSize);
        if (deleted > 0) {
            log.info("RefreshTokenCleanup: deleted {} row(s) [bucket=default, cutoff={}]", deleted, cutoff);
        }
        return deleted;
    }

    @Transactional
    public int cleanupExtendedRetentionBatch(Instant cutoff, int batchSize) {
        int deleted = repository.deleteNonRotatedBatch(cutoff, batchSize);
        if (deleted > 0) {
            log.info("RefreshTokenCleanup: deleted {} row(s) [bucket=extended-retention, cutoff={}]", deleted, cutoff);
        }
        return deleted;
    }

    public List<String> findUnrecognizedRevokeReasons(List<String> knownSecurityReasons) {
        return repository.findUnrecognizedRevokeReasons(knownSecurityReasons);
    }
}
