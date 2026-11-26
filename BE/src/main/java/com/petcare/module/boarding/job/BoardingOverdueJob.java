package com.petcare.module.boarding.job;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.service.BoardingOverdueService;
import com.petcare.module.boarding.service.BoardingOverdueService.DueRow;
import com.petcare.module.boarding.service.BoardingOverdueService.FollowUpResult;
import com.petcare.module.boarding.service.BoardingOverdueService.OverdueResult;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;
import com.petcare.platform.metrics.JobMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * ST15 — đánh dấu lưu trú quá hạn đón và theo dõi (BR-LT-10, docs/adr/0086; cơ chế job: docs/adr/0007). Hai lượt trong
 * một lần chạy, cùng ngân sách thời gian: (1) {@code CHECKED_IN} quá giờ trả quy định của ngày trả dự kiến → {@code
 * OVERDUE}; (2) {@code OVERDUE} → thông báo khách mỗi ngày, Care Task, báo quản lý. Đọc theo lô (keyset), mỗi đặt chỗ
 * một transaction ở {@link BoardingOverdueService}. Không {@code @Transactional} ở đây. Một đặt chỗ lỗi được log
 * {@code BOARDING_OVERDUE_FAILED} rồi bỏ qua; lượt sau thử lại.
 */
@Slf4j
@Component
@EnableConfigurationProperties(BoardingOverdueProperties.class)
public class BoardingOverdueJob {

    private final BoardingOverdueService overdue;
    private final BoardingOverdueProperties properties;
    private final Clock clock;
    private final LongSupplier ticker;
    private JobMetrics jobMetrics = JobMetrics.NOOP;

    @Autowired
    public BoardingOverdueJob(BoardingOverdueService overdue, BoardingOverdueProperties properties, Clock clock) {
        this(overdue, properties, clock, System::nanoTime);
    }

    BoardingOverdueJob(BoardingOverdueService overdue, BoardingOverdueProperties properties, Clock clock,
            LongSupplier ticker) {
        this.overdue = overdue;
        this.properties = properties;
        this.clock = clock;
        this.ticker = ticker;
    }

    /** Kết quả từng lượt chạy cho Prometheus (docs/adr/0155); job dựng tay trong test giữ {@link JobMetrics#NOOP}. */
    @Autowired
    void setJobMetrics(JobMetrics jobMetrics) {
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(cron = "${app.jobs.boarding-overdue.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        long started = ticker.getAsLong();
        int marked = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        boolean stoppedByBudget = false;
        LocalDate today = null;
        try {
            today = LocalDate.now(clock);
            LocalDate afterDate = null;
            long afterId = 0;
            List<DueRow> rows;
            first:
            do {
                rows = overdue.findCheckedInDue(today, afterDate, afterId, properties.batchSize());
                for (DueRow row : rows) {
                    if (overBudget(started)) {
                        stoppedByBudget = true;
                        break first;
                    }
                    try {
                        if (overdue.markOverdue(row.id(), row.petId()) == OverdueResult.MARKED) {
                            marked++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("BOARDING_OVERDUE_FAILED bookingId={} step=mark exception={}", row.id(),
                                e.getClass().getSimpleName(), e);
                    }
                    afterDate = row.checkOutDate();
                    afterId = row.id();
                }
            } while (rows.size() == properties.batchSize());

            afterId = 0;
            second:
            do {
                if (stoppedByBudget) {
                    break;
                }
                rows = overdue.findOverdue(afterId, properties.batchSize());
                for (DueRow row : rows) {
                    if (overBudget(started)) {
                        stoppedByBudget = true;
                        break second;
                    }
                    try {
                        if (overdue.followUp(row.id(), row.petId()) == FollowUpResult.UPDATED) {
                            updated++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("BOARDING_OVERDUE_FAILED bookingId={} step=follow-up exception={}", row.id(),
                                e.getClass().getSimpleName(), e);
                    }
                    afterId = row.id();
                }
            } while (rows.size() == properties.batchSize());
            log.info("BOARDING_OVERDUE marked={} updated={} skipped={} failed={} stoppedByBudget={} today={} "
                    + "durationMs={}", marked, updated, skipped, failed, stoppedByBudget, today, elapsedMs(started));
            jobMetrics.record("boarding_overdue", JobMetrics.Outcome.finished(stoppedByBudget),
                    Map.of("marked", marked, "updated", updated, "skipped", skipped, "failed", failed));
        } catch (RuntimeException e) {
            log.error("BOARDING_OVERDUE_FAILED markedSoFar={} today={} durationMs={}", marked, today,
                    elapsedMs(started), e);
            jobMetrics.record("boarding_overdue", JobMetrics.Outcome.FAILED,
                    Map.of("marked", marked, "updated", updated, "skipped", skipped, "failed", failed));
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private boolean overBudget(long started) {
        return ticker.getAsLong() - started >= properties.timeBudget().toNanos();
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(ticker.getAsLong() - started).toMillis();
    }
}
