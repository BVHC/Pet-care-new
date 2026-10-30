package com.petcare.module.inventory.job;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.inventory.service.StockAlertDigestService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST08 — mỗi ngày báo quản lý chi nhánh tồn dưới ngưỡng, lô sắp / đã hết hạn (BR-KO-07, docs/adr/0087; cơ chế job:
 * docs/adr/0007). Đọc danh sách chi nhánh {@code ACTIVE} một lần, mỗi chi nhánh một transaction ở
 * {@link StockAlertDigestService}. Không {@code @Transactional} ở đây. Một chi nhánh lỗi được log
 * {@code STOCK_ALERT_DIGEST_FAILED} rồi bỏ qua; dừng khi hết {@code timeBudget}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(StockAlertDigestProperties.class)
public class StockAlertDigestJob {

    private final StockAlertDigestService digest;
    private final StockAlertDigestProperties properties;
    private final LongSupplier ticker;

    @Autowired
    public StockAlertDigestJob(StockAlertDigestService digest, StockAlertDigestProperties properties) {
        this(digest, properties, System::nanoTime);
    }

    StockAlertDigestJob(StockAlertDigestService digest, StockAlertDigestProperties properties, LongSupplier ticker) {
        this.digest = digest;
        this.properties = properties;
        this.ticker = ticker;
    }

    @Scheduled(cron = "${app.jobs.stock-alert-digest.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        try {
            List<BranchSummary> branches = digest.findActiveBranches();
            for (BranchSummary branch : branches) {
                if (ticker.getAsLong() - started >= properties.timeBudget().toNanos()) {
                    stoppedByBudget = true;
                    break;
                }
                try {
                    if (digest.notifyBranch(branch.branchId(), branch.name())) {
                        sent++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.error("STOCK_ALERT_DIGEST_FAILED branchId={} exception={}", branch.branchId(),
                            e.getClass().getSimpleName(), e);
                }
            }
            log.info("STOCK_ALERT_DIGEST_RUN branches={} sent={} skipped={} failed={} stoppedByBudget={} durationMs={}",
                    branches.size(), sent, skipped, failed, stoppedByBudget, elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("STOCK_ALERT_DIGEST_FAILED sentSoFar={} durationMs={}", sent, elapsedMs(started), e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
