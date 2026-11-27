package com.petcare.module.shop.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.shop.service.OnlineOrderAutoConfirmService;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.DueOrder;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.Result;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;
import com.petcare.platform.metrics.JobMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * ST12 — tự chuyển đơn online {@code DELIVERED} quá [CFG] {@code shop.auto_confirm_days} ngày sang {@code COMPLETED}
 * (BR-BH-12, docs/adr/0146; cơ chế job: docs/adr/0007). Đọc theo lô (keyset theo
 * {@code delivered_at} rồi id), mỗi đơn một transaction riêng ở {@link OnlineOrderAutoConfirmService}. Không
 * {@code @Transactional} ở đây. Một đơn lỗi được log {@code ONLINE_ORDER_AUTO_CONFIRM_FAILED} rồi bỏ qua; lượt sau thử
 * lại. Mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OnlineOrderAutoConfirmProperties.class)
public class OnlineOrderAutoConfirmJob {

    private final OnlineOrderAutoConfirmService autoConfirm;
    private final OnlineOrderAutoConfirmProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    @Autowired
    public OnlineOrderAutoConfirmJob(OnlineOrderAutoConfirmService autoConfirm,
            OnlineOrderAutoConfirmProperties properties, Clock clock) {
        this(autoConfirm, properties, clock, System::nanoTime);
    }

    OnlineOrderAutoConfirmJob(OnlineOrderAutoConfirmService autoConfirm, OnlineOrderAutoConfirmProperties properties,
            Clock clock, LongSupplier ticker) {
        this.autoConfirm = autoConfirm;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${app.jobs.online-order-auto-confirm.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int confirmed = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        Instant now = null;
        try {
            now = Instant.now(clock);
            Instant afterAt = null;
            long afterId = 0;
            List<DueOrder> rows;
            batches:
            do {
                rows = autoConfirm.findDue(now, afterAt, afterId, properties.batchSize());
                for (DueOrder row : rows) {
                    if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                        stoppedByBudget = true;
                        break batches;
                    }
                    try {
                        if (autoConfirm.confirm(row.id()) == Result.CONFIRMED) {
                            confirmed++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("ONLINE_ORDER_AUTO_CONFIRM_FAILED onlineOrderId={} exception={}", row.id(),
                                e.getClass().getSimpleName(), e);
                    }
                    afterAt = row.deliveredAt();
                    afterId = row.id();
                }
            } while (rows.size() == properties.batchSize());
            log.info("ONLINE_ORDER_AUTO_CONFIRM confirmed={} skipped={} failed={} stoppedByBudget={} now={} durationMs={}",
                    confirmed, skipped, failed, stoppedByBudget, now, elapsedMs(started));
            jobMetrics.record("online_order_auto_confirm", JobMetrics.Outcome.finished(stoppedByBudget),
                    Map.of("confirmed", confirmed, "skipped", skipped, "failed", failed));
        } catch (RuntimeException e) {
            log.error("ONLINE_ORDER_AUTO_CONFIRM_FAILED confirmedSoFar={} now={} durationMs={}", confirmed, now,
                    elapsedMs(started), e);
            jobMetrics.record("online_order_auto_confirm", JobMetrics.Outcome.FAILED,
                    Map.of("confirmed", confirmed, "skipped", skipped, "failed", failed));
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
