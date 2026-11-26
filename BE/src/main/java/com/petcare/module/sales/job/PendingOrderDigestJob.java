package com.petcare.module.sales.job;

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

import com.petcare.module.sales.repository.SalesOrderRepository.BranchOverdue;
import com.petcare.module.sales.service.PendingOrderDigestService;
import com.petcare.module.sales.service.PendingOverdueCutoff;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;
import com.petcare.platform.metrics.JobMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * ST13 — cuối ngày liệt kê Order {@code PENDING} quá hạn cho quản lý (BR-BH-05, docs/adr/0064; cơ chế job:
 * docs/adr/0007). Một lần đọc chi nhánh có Order quá hạn, mỗi chi nhánh một transaction ở
 * {@link PendingOrderDigestService}. Không {@code @Transactional} ở đây. Một chi nhánh lỗi được log
 * {@code PENDING_ORDER_DIGEST_FAILED} rồi bỏ qua; mỗi lượt dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PendingOrderDigestProperties.class)
public class PendingOrderDigestJob {

    private final PendingOrderDigestService digest;
    private final PendingOverdueCutoff cutoff;
    private final PendingOrderDigestProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    @Autowired
    public PendingOrderDigestJob(PendingOrderDigestService digest, PendingOverdueCutoff cutoff,
            PendingOrderDigestProperties properties, Clock clock) {
        this(digest, cutoff, properties, clock, System::nanoTime);
    }

    PendingOrderDigestJob(PendingOrderDigestService digest, PendingOverdueCutoff cutoff,
            PendingOrderDigestProperties properties, Clock clock, LongSupplier ticker) {
        this.digest = digest;
        this.cutoff = cutoff;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${app.jobs.pending-order-digest.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        Instant now = Instant.now(clock);
        int sent = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            int days = cutoff.days();
            Instant mark = cutoff.cutoffFor(days);
            List<BranchOverdue> branches = digest.findOverdueBranches(mark);
            for (BranchOverdue branch : branches) {
                if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                    stoppedByBudget = true;
                    break;
                }
                try {
                    digest.notifyPendingOrders(branch.getBranchId(), branch.getOrderCount(),
                            branch.getOldestPendingAt(), days);
                    sent++;
                } catch (RuntimeException e) {
                    failed++;
                    log.error("PENDING_ORDER_DIGEST_FAILED branchId={} exception={}", branch.getBranchId(),
                            e.getClass().getSimpleName(), e);
                }
            }
            log.info("PENDING_ORDER_DIGEST_RUN branches={} sent={} failed={} stoppedByBudget={} now={} durationMs={}",
                    branches.size(), sent, failed, stoppedByBudget, now, elapsedMs(started));
            jobMetrics.record("pending_order_digest", JobMetrics.Outcome.finished(stoppedByBudget),
                    Map.of("sent", sent, "failed", failed));
        } catch (RuntimeException e) {
            log.error("PENDING_ORDER_DIGEST_FAILED sentSoFar={} now={} durationMs={}", sent, now, elapsedMs(started),
                    e);
            jobMetrics.record("pending_order_digest", JobMetrics.Outcome.FAILED,
                    Map.of("sent", sent, "failed", failed));
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
