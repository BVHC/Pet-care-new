package com.petcare.module.identity.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.service.PendingAccountCleanupService;
import com.petcare.module.identity.service.PendingAccountCleanupService.PurgeResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST02 — dọn tài khoản {@code PENDING} quá hạn (BR-TK-08, docs/adr/0013; cơ chế job: docs/adr/0007). Đọc id quá hạn
 * theo lô (keyset {@code id}), mỗi tài khoản xóa trong một transaction riêng ở {@link PendingAccountCleanupService}.
 * Không {@code @Transactional} ở đây. Một tài khoản lỗi được log rồi bỏ qua; lượt sau thử lại vì điều kiện chỉ dựa trên
 * {@code status} và {@code pending_expires_at}. Mỗi lượt dừng khi hết {@code timeBudget}, để chi phí kiểm FK khi xóa
 * {@code accounts} (nợ D009) không chiếm DB lâu; xóa chậm hơn {@code slowWarn} thì log WARN.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PendingAccountCleanupProperties.class)
public class PendingAccountCleanupJob {

    private final PendingAccountCleanupService cleanup;
    private final PendingAccountCleanupProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public PendingAccountCleanupJob(PendingAccountCleanupService cleanup, PendingAccountCleanupProperties properties,
            Clock clock) {
        this(cleanup, properties, clock, System::nanoTime);
    }

    PendingAccountCleanupJob(PendingAccountCleanupService cleanup, PendingAccountCleanupProperties properties,
            Clock clock, LongSupplier ticker) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.pending-account-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        RunStats stats = new RunStats();
        try {
            long lastId = 0;
            List<Long> ids;
            batches:
            do {
                ids = cleanup.findExpiredIds(now, lastId, properties.batchSize());
                for (Long id : ids) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stats.stoppedByBudget = true;
                        break batches;
                    }
                    purgeOne(id, now, stats);
                    lastId = id;
                }
            } while (ids.size() == properties.batchSize());
            log.info("PENDING_ACCOUNT_CLEANUP deleted={} skipped={} failed={} maxPurgeMs={} avgPurgeMs={} "
                    + "stoppedByBudget={} now={} durationMs={}", stats.deleted, stats.skipped, stats.failed,
                    toMs(stats.maxPurgeNanos), toMs(stats.averagePurgeNanos()), stats.stoppedByBudget, now,
                    elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("PENDING_ACCOUNT_CLEANUP_FAILED deletedSoFar={} now={} durationMs={}", stats.deleted, now,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** Một tài khoản: lỗi chỉ ảnh hưởng tài khoản đó (transaction của nó đã rollback), lượt chạy đi tiếp. */
    private void purgeOne(long accountId, Instant now, RunStats stats) {
        long purgeStarted = ticker.getAsLong();
        try {
            if (cleanup.purge(accountId, now) == PurgeResult.DELETED) {
                stats.deleted++;
            } else {
                stats.skipped++;
            }
        } catch (RuntimeException e) {
            stats.failed++;
            log.error("PENDING_ACCOUNT_CLEANUP_FAILED accountId={}", accountId, e);
        }
        long purgeNanos = ticker.getAsLong() - purgeStarted;
        stats.record(purgeNanos);
        if (purgeNanos > properties.slowWarn().toNanos()) {
            log.warn("PENDING_ACCOUNT_CLEANUP_SLOW accountId={} durationMs={}", accountId, toMs(purgeNanos));
        }
    }

    private long elapsedMs(long started) {
        return toMs(ticker.getAsLong() - started);
    }

    private static long toMs(long nanos) {
        return Duration.ofNanos(nanos).toMillis();
    }

    /** Số đếm của một lượt chạy. */
    private static final class RunStats {
        private int deleted;
        private int skipped;
        private int failed;
        private boolean stoppedByBudget;
        private int purges;
        private long totalPurgeNanos;
        private long maxPurgeNanos;

        void record(long purgeNanos) {
            purges++;
            totalPurgeNanos += purgeNanos;
            maxPurgeNanos = Math.max(maxPurgeNanos, purgeNanos);
        }

        long averagePurgeNanos() {
            return purges == 0 ? 0 : totalPurgeNanos / purges;
        }
    }
}
