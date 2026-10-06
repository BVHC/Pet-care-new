package com.petcare.module.identity.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.service.SessionCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Dọn bảng {@code sessions} hằng ngày (docs/adr/0008, cơ chế job: docs/adr/0007). Xóa phiên có
 * {@code expires_at < now − retentionDays}, theo lô, mỗi lô một transaction ở {@link SessionCleanupService}.
 * Không {@code @Transactional} ở đây: lô lỗi không được rollback các lô đã xóa. Không ghi audit (không thuộc BR-QT-15).
 * Lỗi được log và nuốt; lượt sau xóa bù vì điều kiện chỉ dựa trên thời điểm.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SessionCleanupProperties.class)
public class SessionCleanupJob {

    private final SessionCleanupService cleanup;
    private final SessionCleanupProperties properties;
    private final Clock clock;

    public SessionCleanupJob(SessionCleanupService cleanup, SessionCleanupProperties properties, Clock clock) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jobs.session-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = System.nanoTime();
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(properties.retentionDays()));
        int batchSize = properties.batchSize();
        long deleted = 0;
        try {
            int batch;
            do {
                batch = cleanup.deleteExpiredBatch(cutoff, batchSize);
                deleted += batch;
            } while (batch == batchSize);
            log.info("SESSION_CLEANUP deleted={} cutoff={} durationMs={}", deleted, cutoff, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("SESSION_CLEANUP_FAILED deletedSoFar={} cutoff={} durationMs={}", deleted, cutoff,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
