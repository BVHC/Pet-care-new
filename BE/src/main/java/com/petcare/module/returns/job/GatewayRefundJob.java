package com.petcare.module.returns.job;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.returns.repository.GatewayRefundDao;
import com.petcare.module.returns.service.GatewayRefundService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.metrics.JobMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Quét ST21 theo lô giới hạn, mỗi refund một transaction độc lập. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "FAKE", matchIfMissing = true)
@RequiredArgsConstructor
public class GatewayRefundJob {

    private static final int BATCH_SIZE = 20;
    private static final long BUDGET_NANOS = Duration.ofSeconds(10).toNanos();

    private final GatewayRefundDao refunds;
    private final GatewayRefundService service;
    private final Clock clock;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${app.jobs.gateway-refund.cron:-}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        long started = System.nanoTime();
        int processed = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            for (long refundId : refunds.findDue(clock.instant(), BATCH_SIZE)) {
                if (System.nanoTime() - started >= BUDGET_NANOS) {
                    stoppedByBudget = true;
                    break;
                }
                try {
                    service.process(refundId);
                    processed++;
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("GATEWAY_REFUND_FAILED refundId={} exception={}", refundId,
                            ex.getClass().getSimpleName(), ex);
                }
            }
            jobMetrics.record("gateway_refund", JobMetrics.Outcome.finished(stoppedByBudget),
                    Map.of("processed", processed, "failed", failed));
        } catch (RuntimeException ex) {
            // Quy ước job (CLAUDE.md *Jobs*, docs/adr/0155): log rồi nuốt, lượt sau quét lại.
            log.error("GATEWAY_REFUND_JOB_FAILED processedSoFar={}", processed, ex);
            jobMetrics.record("gateway_refund", JobMetrics.Outcome.FAILED,
                    Map.of("processed", processed, "failed", failed));
        }
    }
}
