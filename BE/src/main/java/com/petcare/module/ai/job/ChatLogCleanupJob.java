package com.petcare.module.ai.job;

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

import com.petcare.module.ai.service.ChatLogCleanupService;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST23 — xóa nhật ký hỏi đáp quá hạn lưu (BR-AI-24, docs/adr/0130; cơ chế job: docs/adr/0007). Xóa dòng
 * {@code ai_chat_logs} có {@code created_at < now − ai.log.retention_days} ([CFG], đọc ở mỗi lượt), theo lô, mỗi lô một
 * transaction ở {@link ChatLogCleanupService}. Mỗi lượt dừng khi hết {@code timeBudget} (phần còn lại dọn lượt sau).
 * Không {@code @Transactional} ở đây: lô lỗi không được rollback các lô đã xóa. Lỗi được log và nuốt; lượt sau xóa bù vì
 * điều kiện chỉ dựa trên thời điểm.
 */
@Slf4j
@Component
@EnableConfigurationProperties(ChatLogCleanupProperties.class)
public class ChatLogCleanupJob {

    private final ChatLogCleanupService cleanup;
    private final ChatLogCleanupProperties properties;
    private final SystemConfigApi config;
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public ChatLogCleanupJob(ChatLogCleanupService cleanup, ChatLogCleanupProperties properties, SystemConfigApi config,
            Clock clock) {
        this(cleanup, properties, config, clock, System::nanoTime);
    }

    ChatLogCleanupJob(ChatLogCleanupService cleanup, ChatLogCleanupProperties properties, SystemConfigApi config,
            Clock clock, LongSupplier ticker) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.config = config;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.chat-log-cleanup.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        long deleted = 0;
        Instant cutoff = null;
        boolean budgetHit = false;
        try {
            cutoff = Instant.now(clock).minus(Duration.ofDays(config.getInt(ConfigKey.AI_LOG_RETENTION_DAYS)));
            int batch;
            do {
                if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                    budgetHit = true;
                    break;
                }
                batch = cleanup.deleteExpiredBatch(cutoff, properties.batchSize());
                deleted += batch;
            } while (batch == properties.batchSize());
            log.info("CHAT_LOG_CLEANUP deleted={} cutoff={} budgetHit={} durationMs={}", deleted, cutoff, budgetHit,
                    elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("CHAT_LOG_CLEANUP_FAILED deletedSoFar={} cutoff={} durationMs={}", deleted, cutoff,
                    elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
