package com.petcare.platform.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Job dọn định kỳ refresh_tokens (ADR-0003) — job scheduled đầu tiên trong
 * repo, xem platform/config/SchedulingConfig.java cho @EnableScheduling.
 * Không có distributed lock: an toàn vì hiện chỉ có 1 backend instance
 * (docker-compose.yml); bắt buộc bổ sung lock (ShedLock/advisory lock) trước
 * khi scale-out nhiều instance.
 */
@Component
@ConditionalOnProperty(prefix = "app.refresh-token-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenCleanupService cleanupService;
    private final int retentionDaysDefault;
    private final int retentionDaysExtended;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final List<String> knownSecurityReasons;

    public RefreshTokenCleanupJob(
            RefreshTokenCleanupService cleanupService,
            @Value("${app.refresh-token-cleanup.retention-days-default:7}") int retentionDaysDefault,
            @Value("${app.refresh-token-cleanup.retention-days-extended:30}") int retentionDaysExtended,
            @Value("${app.refresh-token-cleanup.batch-size:500}") int batchSize,
            @Value("${app.refresh-token-cleanup.max-batches-per-run:200}") int maxBatchesPerRun,
            @Value("${app.refresh-token-cleanup.known-security-reasons:LOGOUT,LOCK_ACCOUNT,DEACTIVATE_ACCOUNT}") List<String> knownSecurityReasons) {
        this.cleanupService = cleanupService;
        this.retentionDaysDefault = retentionDaysDefault;
        this.retentionDaysExtended = retentionDaysExtended;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.knownSecurityReasons = knownSecurityReasons;
    }

    @Scheduled(cron = "${app.refresh-token-cleanup.cron:0 30 2 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void cleanupExpiredRefreshTokens() {
        Instant now = Instant.now();
        Instant cutoffDefault = now.minus(retentionDaysDefault, ChronoUnit.DAYS);
        Instant cutoffExtended = now.minus(retentionDaysExtended, ChronoUnit.DAYS);

        int deletedDefault = runBatchLoop("default(" + retentionDaysDefault + "d)",
                () -> cleanupService.cleanupExpiredOrRotatedBatch(cutoffDefault, batchSize));
        int deletedExtended = runBatchLoop("extended-retention(" + retentionDaysExtended + "d)",
                () -> cleanupService.cleanupExtendedRetentionBatch(cutoffExtended, batchSize));

        List<String> unrecognized = cleanupService.findUnrecognizedRevokeReasons(knownSecurityReasons);
        if (!unrecognized.isEmpty()) {
            log.warn("RefreshTokenCleanupJob found unrecognized revoke_reason values not in "
                    + "known-security-reasons: {} — these rows are still retained under the "
                    + "extended-retention bucket by default, but consider documenting them", unrecognized);
        }

        log.info("RefreshTokenCleanupJob run complete: deletedDefault={}, deletedExtended={}, totalDeleted={}",
                deletedDefault, deletedExtended, deletedDefault + deletedExtended);
    }

    private int runBatchLoop(String bucketLabel, IntSupplier batchFn) {
        int total = 0;
        int batches = 0;
        int deletedThisBatch;
        do {
            deletedThisBatch = batchFn.getAsInt();
            total += deletedThisBatch;
            batches++;
        } while (deletedThisBatch == batchSize && batches < maxBatchesPerRun);

        if (batches >= maxBatchesPerRun && deletedThisBatch == batchSize) {
            log.warn("RefreshTokenCleanupJob bucket={} reached max-batches-per-run={} — remaining "
                    + "eligible rows will be handled on the next scheduled run", bucketLabel, maxBatchesPerRun);
        }
        log.info("RefreshTokenCleanupJob bucket={} deletedRows={} batches={}", bucketLabel, total, batches);
        return total;
    }
}
