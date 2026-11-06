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

import com.petcare.module.identity.service.OtpTokenCleanupService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Dọn bảng {@code otp_tokens} hằng ngày (docs/adr/0111, trả D012; cơ chế job: docs/adr/0007). Xóa mã có
 * {@code expires_at < now − retentionDays}, theo lô, mỗi lô một transaction ở {@link OtpTokenCleanupService}; mã còn
 * hiệu lực và mã trong cửa sổ quota BR-TK-07 không bao giờ bị chọn. Mỗi lượt dừng khi hết {@code timeBudget} (phần còn
 * lại dọn lượt sau) để một đợt tồn đọng lớn không giữ thread scheduler lâu. Không {@code @Transactional} ở đây: lô lỗi
 * không được rollback các lô đã xóa. Không ghi audit (không thuộc BR-QT-15). Lỗi được log và nuốt; lượt sau xóa bù vì
 * điều kiện chỉ dựa trên thời điểm.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OtpTokenCleanupProperties.class)
public class OtpTokenCleanupJob {

    private final OtpTokenCleanupService cleanup;
    private final OtpTokenCleanupProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public OtpTokenCleanupJob(OtpTokenCleanupService cleanup, OtpTokenCleanupProperties properties, Clock clock) {
        this(cleanup, properties, clock, System::nanoTime);
    }

    OtpTokenCleanupJob(OtpTokenCleanupService cleanup, OtpTokenCleanupProperties properties, Clock clock,
            LongSupplier ticker) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.otp-token-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
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
            log.info("OTP_TOKEN_CLEANUP deleted={} cutoff={} budgetHit={} durationMs={}", deleted, cutoff, budgetHit,
                    elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("OTP_TOKEN_CLEANUP_FAILED deletedSoFar={} cutoff={} durationMs={}", deleted, cutoff,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
