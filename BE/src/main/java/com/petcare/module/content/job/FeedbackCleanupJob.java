package com.petcare.module.content.job;

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

import com.petcare.module.content.service.FeedbackCleanupService;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST24 — xóa cứng feedback quá hạn lưu (BR-DG-05, docs/adr/0122, 0131; cơ chế job: docs/adr/0007). Xóa dòng
 * {@code feedbacks} có {@code created_at < now − feedback.retention_days} ([CFG], đọc ở mỗi lượt), theo lô, mỗi lô một
 * transaction ở {@link FeedbackCleanupService}. Sau mỗi lô đầy nghỉ {@code batchGap} để một đợt tồn đọng lớn không làm
 * chậm request; mỗi lượt dừng khi hết {@code timeBudget} (phần còn lại dọn lượt sau). Không {@code @Transactional} ở
 * đây: lô lỗi không được rollback các lô đã xóa. Lỗi được log và nuốt; lượt sau xóa bù vì điều kiện chỉ dựa trên thời
 * điểm. Đây là xóa không hoàn tác, nên hạn lưu ngoài khoảng của khóa (7–365, V51) thì bỏ lượt chứ không xóa. Tắt job:
 * {@code FEEDBACK_CLEANUP_CRON=-}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(FeedbackCleanupProperties.class)
public class FeedbackCleanupJob {

    /** Khoảng hợp lệ của {@code feedback.retention_days} (V51). Chốt chặn thứ hai: xóa là không hoàn tác. */
    static final int MIN_RETENTION_DAYS = 7;
    static final int MAX_RETENTION_DAYS = 365;

    /** Nghỉ giữa hai lô; tách ra để test không ngủ thật. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final FeedbackCleanupService cleanup;
    private final FeedbackCleanupProperties properties;
    private final SystemConfigApi config;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Sleeper sleeper;

    @Autowired
    public FeedbackCleanupJob(FeedbackCleanupService cleanup, FeedbackCleanupProperties properties,
            SystemConfigApi config, Clock clock) {
        this(cleanup, properties, config, clock, System::nanoTime, duration -> Thread.sleep(duration));
    }

    FeedbackCleanupJob(FeedbackCleanupService cleanup, FeedbackCleanupProperties properties, SystemConfigApi config,
            Clock clock, LongSupplier ticker, Sleeper sleeper) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.config = config;
        this.clock = clock;
        this.ticker = ticker;
        this.sleeper = sleeper;
    }

    @Scheduled(cron = "${app.jobs.feedback-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        long deleted = 0;
        int batches = 0;
        Instant cutoff = null;
        boolean budgetHit = false;
        try {
            int retentionDays = config.getInt(ConfigKey.FEEDBACK_RETENTION_DAYS);
            if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
                log.error("FEEDBACK_CLEANUP_SKIPPED retentionDays={} allowed={}..{} durationMs={}", retentionDays,
                        MIN_RETENTION_DAYS, MAX_RETENTION_DAYS, elapsedMs(started));
                return;
            }
            cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
            int batchSize = properties.batchSize();
            long budgetNanos = properties.timeBudget().toNanos();
            int batch;
            do {
                if (ticker.getAsLong() - started >= budgetNanos) {
                    budgetHit = true;
                    break;
                }
                batch = cleanup.deleteExpiredBatch(cutoff, batchSize);
                deleted += batch;
                batches++;
                if (batch == batchSize && !properties.batchGap().isZero() && !pause()) {
                    break;
                }
            } while (batch == batchSize);
            log.info("FEEDBACK_CLEANUP deleted={} batches={} cutoff={} budgetHit={} durationMs={}", deleted, batches,
                    cutoff, budgetHit, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("FEEDBACK_CLEANUP_FAILED deletedSoFar={} batches={} cutoff={} durationMs={}", deleted, batches,
                    cutoff, elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** Nghỉ {@code batchGap}; trả {@code false} (và giữ cờ ngắt) nếu bị ngắt, khi đó lượt dừng, lượt sau làm tiếp. */
    private boolean pause() {
        try {
            sleeper.sleep(properties.batchGap());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FEEDBACK_CLEANUP_INTERRUPTED");
            return false;
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
