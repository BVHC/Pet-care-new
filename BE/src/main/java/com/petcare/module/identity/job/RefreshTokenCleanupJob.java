package com.petcare.module.identity.job;

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

import com.petcare.module.identity.service.RefreshTokenCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Dọn bảng {@code refresh_tokens} hằng ngày (docs/adr/0114, trả D054; cơ chế job: docs/adr/0007). Xóa token có
 * {@code expires_at < now − retentionDays}, theo lô, mỗi lô một transaction ở {@link RefreshTokenCleanupService}.
 * {@code expires_at} bằng hạn phiên nên token của phiên còn hiệu lực không bao giờ bị chọn, và token đã dùng chỉ bị xóa
 * khi phiên của nó đã hết hạn (lúc đó refresh đã từ chối, không còn gì để phát hiện dùng lại). Mỗi lượt dừng khi hết
 * {@code timeBudget} (phần còn lại dọn lượt sau) để một đợt tồn đọng lớn không giữ thread scheduler lâu. Không
 * {@code @Transactional} ở đây: lô lỗi không được rollback các lô đã xóa. Không ghi audit (không thuộc BR-QT-15). Lỗi
 * được log và nuốt; lượt sau xóa bù vì điều kiện chỉ dựa trên thời điểm.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RefreshTokenCleanupProperties.class)
public class RefreshTokenCleanupJob {

    private final RefreshTokenCleanupService cleanup;
    private final RefreshTokenCleanupProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public RefreshTokenCleanupJob(RefreshTokenCleanupService cleanup, RefreshTokenCleanupProperties properties,
            Clock clock) {
        this(cleanup, properties, clock, System::nanoTime);
    }

    RefreshTokenCleanupJob(RefreshTokenCleanupService cleanup, RefreshTokenCleanupProperties properties, Clock clock,
            LongSupplier ticker) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.refresh-token-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(properties.retentionDays()));
        int batchSize = properties.batchSize();
        long budgetNanos = properties.timeBudget().toNanos();
        long deleted = 0;
        boolean budgetHit = false;
        try {
            int batch;
            do {
                if (ticker.getAsLong() - started >= budgetNanos) {
                    budgetHit = true;
                    break;
                }
                batch = cleanup.deleteExpiredBatch(cutoff, batchSize);
                deleted += batch;
            } while (batch == batchSize);
            log.info("REFRESH_TOKEN_CLEANUP deleted={} cutoff={} budgetHit={} durationMs={}", deleted, cutoff,
                    budgetHit, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("REFRESH_TOKEN_CLEANUP_FAILED deletedSoFar={} cutoff={} durationMs={}", deleted, cutoff,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
