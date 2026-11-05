package com.petcare.module.care.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.care.service.NotificationCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Dọn bảng {@code notifications} hằng ngày (docs/adr/0112, trả D023; cơ chế job: docs/adr/0007). Hai lượt tuần tự theo
 * lô, mỗi lô một transaction ở {@link NotificationCleanupService}: thông báo <b>đã đọc</b> có {@code created_at < now −
 * readRetentionDays}, rồi thông báo <b>chưa đọc</b> có {@code created_at < now − unreadRetentionDays}. Thông báo mới
 * (dưới {@code readRetentionDays}) không bao giờ bị chọn. Một {@code timeBudget} dùng chung cho cả hai lượt; hết thì
 * dừng, phần còn lại dọn lượt sau, để một đợt tồn đọng lớn không giữ thread scheduler lâu. Không
 * {@code @Transactional} ở đây: lô lỗi không được rollback các lô đã xóa. Không ghi audit (không thuộc BR-QT-15). Lỗi
 * được log và nuốt; lượt sau xóa bù vì điều kiện chỉ dựa trên thời điểm và {@code read_at}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(NotificationCleanupProperties.class)
public class NotificationCleanupJob {

    private final NotificationCleanupService cleanup;
    private final NotificationCleanupProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public NotificationCleanupJob(NotificationCleanupService cleanup, NotificationCleanupProperties properties,
            Clock clock) {
        this(cleanup, properties, clock, System::nanoTime);
    }

    NotificationCleanupJob(NotificationCleanupService cleanup, NotificationCleanupProperties properties, Clock clock,
            LongSupplier ticker) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.notification-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        Instant readCutoff = now.minus(Duration.ofDays(properties.readRetentionDays()));
        Instant unreadCutoff = now.minus(Duration.ofDays(properties.unreadRetentionDays()));
        long[] deleted = new long[2];
        boolean budgetHit = false;
        try {
            budgetHit = deleteAll(true, readCutoff, deleted, started);
            if (!budgetHit) {
                budgetHit = deleteAll(false, unreadCutoff, deleted, started);
            }
            log.info("NOTIFICATION_CLEANUP read={} unread={} readCutoff={} unreadCutoff={} budgetHit={} durationMs={}",
                    deleted[0], deleted[1], readCutoff, unreadCutoff, budgetHit, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("NOTIFICATION_CLEANUP_FAILED readSoFar={} unreadSoFar={} durationMs={}", deleted[0], deleted[1],
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /**
     * Xóa theo lô tới khi lô cuối thiếu; cộng dồn vào {@code deleted[0]} (đã đọc) hoặc {@code deleted[1]} (chưa đọc)
     * sau mỗi lô để log đúng khi lô sau lỗi. Trả {@code true} nếu dừng vì hết {@code timeBudget}.
     */
    private boolean deleteAll(boolean read, Instant cutoff, long[] deleted, long started) {
        int batchSize = properties.batchSize();
        long budgetNanos = properties.timeBudget().toNanos();
        int batch;
        do {
            if (ticker.getAsLong() - started >= budgetNanos) {
                return true;
            }
            batch = read ? cleanup.deleteReadBatch(cutoff, batchSize) : cleanup.deleteUnreadBatch(cutoff, batchSize);
            deleted[read ? 0 : 1] += batch;
        } while (batch == batchSize);
        return false;
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
