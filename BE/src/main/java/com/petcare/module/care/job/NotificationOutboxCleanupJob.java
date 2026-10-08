package com.petcare.module.care.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.care.entity.OutboxStatus;
import com.petcare.module.care.service.NotificationOutboxCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Dọn {@code notification_outbox} hằng ngày (docs/adr/0016, cơ chế job: docs/adr/0007): xóa dòng {@code SENT} có
 * {@code created_at < now − sentRetentionDays}, rồi dòng {@code FAILED} có {@code created_at < now −
 * failedRetentionDays}, theo lô, mỗi lô một transaction ở {@link NotificationOutboxCleanupService}. Dòng
 * {@code PENDING} không bao giờ bị xóa. Không {@code @Transactional} ở đây; không audit. Lỗi được log và nuốt; lượt
 * sau xóa bù vì điều kiện chỉ dựa trên trạng thái và thời điểm.
 */
@Slf4j
@Component
@EnableConfigurationProperties(NotificationOutboxCleanupProperties.class)
public class NotificationOutboxCleanupJob {

    private final NotificationOutboxCleanupService cleanup;
    private final NotificationOutboxCleanupProperties properties;
    private final Clock clock;

    public NotificationOutboxCleanupJob(NotificationOutboxCleanupService cleanup,
            NotificationOutboxCleanupProperties properties, Clock clock) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jobs.notification-outbox-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = System.nanoTime();
        Instant now = Instant.now(clock);
        Instant sentCutoff = now.minus(Duration.ofDays(properties.sentRetentionDays()));
        Instant failedCutoff = now.minus(Duration.ofDays(properties.failedRetentionDays()));
        long[] deleted = new long[2];
        try {
            deleteAll(OutboxStatus.SENT, sentCutoff, deleted, 0);
            deleteAll(OutboxStatus.FAILED, failedCutoff, deleted, 1);
            log.info("NOTIFICATION_OUTBOX_CLEANUP sent={} failed={} sentCutoff={} failedCutoff={} durationMs={}",
                    deleted[0], deleted[1], sentCutoff, failedCutoff, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("NOTIFICATION_OUTBOX_CLEANUP_FAILED sentSoFar={} failedSoFar={} durationMs={}", deleted[0],
                    deleted[1], elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** Xóa theo lô tới khi lô cuối thiếu; cộng dồn vào {@code deleted[slot]} sau mỗi lô để log đúng khi lô sau lỗi. */
    private void deleteAll(OutboxStatus status, Instant cutoff, long[] deleted, int slot) {
        int batchSize = properties.batchSize();
        int batch;
        do {
            batch = cleanup.deleteProcessedBatch(status, cutoff, batchSize);
            deleted[slot] += batch;
        } while (batch == batchSize);
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
